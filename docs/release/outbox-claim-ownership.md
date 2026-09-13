# Outbox 超时重领保护

日期：2026-09-13。变更前基线：`9456ecc`。迁移：V20。

## 问题与修复

A 领取事件后发送阻塞；领取超时，B 重新领取。原实现的成功回写只比较 ID/status，失败回写只比较 ID，A 晚返回时可能覆盖 B 的 SENDING，甚至把 SENT 改回 PENDING/FAILED。

- V20 给 `outbox_event` 增加可空 UUID `claim_token`，不改历史迁移。
- `claimBatch` 在一个 CTE 语句和独立事务中完成 `SKIP LOCKED` 选取、更新状态/领取时间、生成每行新 token、返回领取结果；原有事件 ID 和 payload 保持不变。
- `refreshClaim`、`markSent`、`markFailed` 均要求 `id + status=SENDING + claim_token` 匹配，并以影响行数是否为 1 返回结果。失去领取权时不修改状态、重试次数、错误信息或时间戳。
- 每条消息发送前条件续租；已在排队期间被重领的消息不再发出。实际发送阻塞超过租期后仍可能被重领，由结果回写校验兜底。
- 当前持有者成功、失败及人工回放均清空 token；后续领取重新生成 token。重试退避沿用现有算法，时间改由数据库计算，避免应用节点时钟差异。
- Relay 仅在回写成功后记录 SENT/FAILED；丢失领取权记录 `fitpilot.outbox.publish` 的 `status=CLAIM_LOST` 及 `OutboxClaimLost` 日志。线程中断保留中断标志并停止处理后续批次条目，剩余领取等待超时恢复。

## 租约与幂等边界

超时仅让其他实例有资格重领；在新 token 替换之前，原领取仍可续租和完成。每条发送前续租增加一次数据库写入，不增加后台续租线程；本次未引入并行发送或宣称吞吐提高。

UUID 是数据库领取身份，不是单调 fencing 序号。它只能保护 Outbox 状态，不能撤回或拦截已经发给 Kafka 的消息，也不保证业务事件严格按产生顺序发布。发送成功但标记前宕机、网络超时后 Broker 实际收到消息等场景仍可能重复投递；继续依靠原 eventId、Inbox 同事务消费及业务唯一约束，语义仍是至少一次投递和幂等业务效果。

## 发布与回滚

必须先停止全部旧版 Relay，并等待其在途执行结束，再执行 V20 并启用新版 Relay。单纯先加列再让新旧 Relay 混跑不安全：旧代码回写不会检查 token。该切换窗口的业务事件可以暂存 Outbox，恢复后由新版发布器补发。

已有无 token 的 SENDING 行保持原领取时间，超时后新版可重领并赋予 token；无需重置全表。数据库迁移先于新版应用启动。回滚保留新增列；回退旧版 Relay 会重新失去领取保护，不能当作具有同等保障的回滚目标。本次没有执行部署。

## 验证设计

`OutboxLeaseIT` 使用真实 PostgreSQL、Flyway 全量迁移和 Spring 事务代理，直接将测试行领取时间调到过去来构造超时，不依赖长时间 sleep：

1. A 过期、B 重领后，A 的续租、晚成功、晚失败均返回 false，B 的完整数据库行不变；B 标记 SENT 后，A 也不能重新打开事件。
2. 当前持有者失败进入退避，再次失败耗尽进入 FAILED；人工回放生成新 token，旧回调不能覆盖，回放后可正常发送完成。
3. 历史无 token 的过期 SENDING 可正常恢复。
4. 一个事务持有行锁时，另一线程领取跳过该行；锁释放后剩余行可被领取。
5. Relay 线程阻塞在受控 Kafka Future，测试线程在真实数据库执行超时重领，再分别释放旧线程的成功/异常结果。验证 B 的状态未被覆盖、旧 Relay 只记录 CLAIM_LOST，B 仍可完成。

`OutboxRelayTest` 覆盖发送前失去领取权时完全不调用 Kafka，以及线程中断后停止批次并保留中断标志。既有 `FitPilotEventFlowIT` 继续覆盖真实 Kafka 事件投影、重复消息业务幂等与毒消息死信；受控 Future 测试本身不宣称模拟了真实 Broker 网络故障。

## 验证结果

2026-09-13 11:54（Asia/Shanghai），Windows + Docker Desktop，设置 `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` 后执行 `mvn verify`，BUILD SUCCESS。66 项单元测试、28 项集成测试，共 94 项，零失败、零错误、零跳过；Flyway V1–V20、JaCoCo 覆盖率门禁及零跳过门禁全部通过。新增 Outbox 验证为 2 项单元测试和 6 项 PostgreSQL 集成场景。

本地日志：`target/outbox-lease-verify.log`；XML：`target/surefire-reports`、`target/failsafe-reports`。`git diff --check` 通过。以上为本地验证，未执行新容量压测、远端 CI 或生产发布。

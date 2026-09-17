# Outbox 积压恢复与领取时机验证

日期：2026-09-17（Asia/Shanghai）。基线提交 `bf3ffc4`，最终未提交源码逐文件 SHA-256 见[实验 manifest](evidence/outbox-backlog-20260917/manifest.json)，最终结果见[results.json](evidence/outbox-backlog-20260917/results.json)。这是本机隔离 PostgreSQL/pgvector、Redis、Kafka Testcontainers 的 Service 写入和真实 Kafka 传输实验；消费者由测试驱动生产 Handler，不是多进程部署或生产容量测试。

## 问题与决定

原 Relay 默认一次领取 100 条，再逐条等待 Kafka Future。每条最多等待配置的 5 秒；在前面的发送变慢时，批次尾部已经持有的 30 秒租约可能过期，被另一实例重领。V20 token 能防止旧领取者覆盖状态，Inbox 能阻止重复业务结果，但无意义的重复发送和追平延迟仍可能增加。

现在**每条事件在发送前才领取**，每轮仍最多处理 `OUTBOX_BATCH_SIZE`（默认 100）条。这样消除“批次尾部排队时租约已过期”的来源；单条发送本身超时后被重领的可能性仍由 token 与 Inbox 处理。没有引入并发发送：当前规模下并行度、同聚合顺序和 Broker 背压的收益尚无证据。`OUTBOX_BATCH_SIZE` 现在是每轮处理上限，不是一次数据库领取数量。

## 场景与结果

运行：`./scripts/run-outbox-backlog.ps1 -RunId final-concurrent-20260917 -BatchSize 100`。实验使用真实 `WorkoutService.complete` 写状态与 Outbox，实际 Kafka Broker，两个 Relay 对象，以及生产 PR、Analytics、Notification Handler；数据库 Inbox 与业务唯一约束保持开启。实验先创建 Topic，确认启动时可获取 Kafka cluster ID，再注入 Broker 中断。

| 场景 | 故障和负载 | 最老积压年龄 | 恢复至 Outbox 清空且投影追平 | 数据护栏 |
|---|---|---:|---:|---|
| Kafka 中断 | 暂停 Broker 时完成 1 次训练并尝试发送，随后恢复 | 3.20 秒 | 2.81 秒 | 训练保持 COMPLETED；4 条 PR、1 条分析、4 条通知；业务重复 0 |
| 双 Relay 重领 | A 已向 Kafka 发出同一事件但被控 Future 阻塞；模拟租约过期后 B 重领并再发，A 晚回写 | 不适用 | 0.41 秒 | Kafka 实际收到两次；旧 token 回写被拒；投影与通知各一份，业务重复 0 |
| 过期长积压 | 40 次真实训练完成；把待发事件的 `created_at` 回拨 120 秒，最终形成约 200 条 Workout/PR 事件 | 120.00 秒（合成回拨） | 3.80 秒 | 40 次训练均完成；各有 4 条 PR、1 条分析、4 条通知；待发/发送中/失败 0，业务重复 0 |

“最老积压年龄”是恢复前 SQL 采样值，不是全过程最大值；长积压的 120 秒由测试显式回拨，不是等待 120 秒形成的真实持续故障。恢复时间从 Broker 恢复或开始排空到上述护栏成立，包含测试进程手动消费和投影处理，不可外推为生产恢复 SLO。每种配置仅运行一次；此前探索性 100 条预领取与 4 条预领取对照未归档为正式基线，不据此声称统计上的性能提升。

## 质量门禁与边界

- 修改后的 `OutboxRelayTest` 断言下一条只在前一条发送并回写后才领取；原 `OutboxLeaseIT` 保留旧领取者晚成功/晚失败、并发重领和死信回放测试。
- 最终源码的 `mvn clean verify` 于 2026-09-17 22:22（Asia/Shanghai）通过：116 项后端测试，零失败、零错误、零跳过；JaCoCo 与零跳过门禁通过。日志保存在本机忽略目录 `release-evidence/outbox-backlog-exact-gate.log`。定向实验留下的 XML 被 clean 清除，没有混入 116 项统计。
- 最终显式实验 `OutboxBacklogBenchmark` 通过；可重跑脚本、结果与源码散列已归档。实验临时日志被后续 `mvn clean verify` 清理，不作为保留证据。本次未重跑前端、远端 CI 或真实生产门禁。
- 冷启动探索运行中，Broker 在 Kafka Template 首次观测查询 cluster ID 前中断，该同步查询使单次发送路径约 95 秒才返回；正常启动后中断的正式运行约 5.9 秒完成故障窗口。此边界与批次尾部过早领取不同，不能用本次逐条领取声称解决。若要求 Kafka 故障时 Relay 的严格时间上限，应单独治理 Template 观测与 producer metadata 等待，并验证 trace 传播代价。
- 本次两个 Relay 位于同一测试进程、不同线程；A 真实发送到 Kafka 后只延迟其确认 Future，B 在数据库模拟租约到期后重领。没有执行两应用实例长期运行、Broker 大规模分区积压或持续写入下的恢复容量测试。投影验证调用的是生产 Handler，但不是 `@KafkaListener` 后台线程；现有 `FitPilotEventFlowIT` 另测真实 Listener 路径。

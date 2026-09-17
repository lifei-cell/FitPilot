# Kafka 与 Outbox 积压处置

## 告警

`fitpilot.outbox.oldest.age.seconds > 60` 或 Kafka consumer lag 持续增长即进入处置。先区分生产、Broker、消费和毒消息四类问题。

## 诊断

1. 查看 Event Dashboard、`GET /api/v1/operations/events/status` 和应用 trace。
2. 检查 Kafka Broker/Topic/ACL、producer 错误、consumer group lag 与数据库连接池。
3. 查询 outbox 的 `PENDING/FAILED` 数量和最老时间，不修改业务 payload。
4. 查看 dead letter，仅使用受审计的 replay API 重放明确可恢复的消息。
5. 若 `fitpilot.outbox.publish{status="CLAIM_LOST"}` 对应计数持续增长，结合 `OutboxClaimLost` 日志检查发送延迟、线程停顿、领取超时及批次排队。该状态表示没有领取权，不是一次已确认的发送失败；不要因此绕过 Inbox 或直接改写状态。

## 恢复

1. Broker 恢复后观察 outbox publisher 自动补发；流量过大时先限写，避免数据库被轮询压垮。
2. 对 `FAILED` outbox 使用 `/api/v1/operations/events/outbox/{eventId}/replay`，逐批执行并观察 lag。
3. 对 dead letter 修复根因后使用 `/dead-letters/{id}/replay`，消费者幂等表必须仍然生效。
4. 只有 pending=0、lag 回落、无重复副作用且 PR/分析投影一致时关闭事故。

禁止直接删除 outbox/dead-letter 行或跳过幂等约束。

V20 发布必须先停止全部旧版 Relay 并等待在途执行结束，再执行迁移并启用新版 Relay；旧版回写没有 token 条件，新旧 Relay 混跑时不能承诺保护生效。回滚保留 V20 列，回退旧版发布器会失去领取保护。详见 [迁移与验证记录](../release/outbox-claim-ownership.md)。

当前 Relay 每次发送前领取 1 条，`OUTBOX_BATCH_SIZE` 限制每轮最多处理的条数。调整该值只改变单轮上限，不改变单次领取数量；不要用扩大上限替代排查 Broker 发送延迟、消费者 lag 和数据库投影耗时。[隔离积压恢复实验](../performance/outbox-backlog-recovery-2026-09-17.md)记录了实际故障注入、追平时间与冷启动边界。

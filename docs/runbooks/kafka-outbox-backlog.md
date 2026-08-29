# Kafka 与 Outbox 积压处置

## 告警

`fitpilot.outbox.oldest.age.seconds > 60` 或 Kafka consumer lag 持续增长即进入处置。先区分生产、Broker、消费和毒消息四类问题。

## 诊断

1. 查看 Event Dashboard、`GET /api/v1/operations/events/status` 和应用 trace。
2. 检查 Kafka Broker/Topic/ACL、producer 错误、consumer group lag 与数据库连接池。
3. 查询 outbox 的 `PENDING/FAILED` 数量和最老时间，不修改业务 payload。
4. 查看 dead letter，仅使用受审计的 replay API 重放明确可恢复的消息。

## 恢复

1. Broker 恢复后观察 outbox publisher 自动补发；流量过大时先限写，避免数据库被轮询压垮。
2. 对 `FAILED` outbox 使用 `/api/v1/operations/events/outbox/{eventId}/replay`，逐批执行并观察 lag。
3. 对 dead letter 修复根因后使用 `/dead-letters/{id}/replay`，消费者幂等表必须仍然生效。
4. 只有 pending=0、lag 回落、无重复副作用且 PR/分析投影一致时关闭事故。

禁止直接删除 outbox/dead-letter 行或跳过幂等约束。

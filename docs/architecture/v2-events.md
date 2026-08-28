# FitPilot V2 事件驱动架构

## 事件链路

```text
完成 Workout
  └─ 同一 PostgreSQL 事务写 WorkoutCompletedEvent 到 outbox_event
       └─ Outbox Relay（SKIP LOCKED，多实例安全）
            └─ Kafka，key = workoutId
                 ├─ PR Projector → personal_record
                 │    └─ PersonalRecordCreatedEvent → 通知消费者
                 └─ Analytics Projector → workout_analytics_projection
```

当前保持模块化单体，Kafka 用于模块解耦和削峰，不提前拆微服务。业务事实仍以 PostgreSQL 为准。

## 事件契约

统一信封字段：`eventId`、`eventType`、`eventVersion`、`aggregateType`、`aggregateId`、
`occurredAt`、`traceId`、`payload`。当前事件版本为 `1`。

| 事件 | Topic | Key | 生产时机 |
|---|---|---|---|
| `WorkoutCompletedEvent` | `fitness.workout.completed` | workoutId | Workout 完成事务 |
| `PersonalRecordCreatedEvent` | `fitness.personal-record.created` | personalRecordId | PR 消费事务 |
| `TrainingPlanCreatedEvent` | `fitness.training-plan.created` | trainingPlanId | 计划创建事务 |
| `TrainingPlanUpdatedEvent` | `fitness.training-plan.updated` | trainingPlanId | 计划激活事务 |

同一聚合使用固定 Key，保证其在同一 Topic 内进入同一分区。事件契约采用向后兼容演进；破坏性变更必须提升 `eventVersion` 并保留旧消费者过渡期。

## 一致性与故障语义

- 事务 Outbox 保证业务写入与“待发送事件”原子提交；Kafka 不可用时完成训练仍成功，事件保持 PENDING 并自动补偿。
- Relay 发送成功后才标记 SENT。发送成功但状态更新前宕机会造成重复，因此交付语义是 at-least-once。
- `processed_event(event_id, consumer)` 与业务投影处在同一数据库事务；重复消息直接跳过。
- `personal_record(workout_set_id, record_type)`、分析投影主键和通知来源事件唯一键提供第二层业务幂等。
- 消费失败先按固定间隔重试，耗尽后同时写入 `dead_letter_event` 并投递原 Topic 的 `.DLT` Topic。
- Relay 抢占使用 `FOR UPDATE SKIP LOCKED`；SENDING 超时事件会重新抢占，避免实例宕机造成永久卡死。

这套设计不承诺 exactly-once，也不把 Redis 作为事件或幂等事实来源。

## 运维与回放

运维 API 必须携带 `X-Operations-Token`，其值由 `EVENT_OPERATIONS_TOKEN` 配置：

```text
GET  /api/v1/operations/events/status
GET  /api/v1/operations/events/dead-letters?limit=50
POST /api/v1/operations/events/dead-letters/{id}/replay
POST /api/v1/operations/events/outbox/{eventId}/replay
```

回放仍会经过消费者 Inbox 幂等校验。修复导致失败的代码或数据后再回放；不要直接修改 `processed_event`。

## 验收场景

1. Kafka 不可用：Workout 完成，Outbox 保持 PENDING，PR/分析暂不可见。
2. Kafka 恢复：Relay 自动发送，Outbox 转为 SENT，PR、分析与通知最终生成。
3. 重复投递：PR、分析和通知条数不增加。
4. 毒消息：重试耗尽后进入数据库死信表与 Kafka `.DLT` Topic，可查询并回放。

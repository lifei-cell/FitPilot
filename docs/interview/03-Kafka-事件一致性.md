# Kafka、事件驱动与最终一致性

## 1. Kafka 核心模型

- **Broker：** Kafka 服务节点。
- **Topic：** 事件分类。
- **Partition：** Topic 的有序追加日志和并行度单位。
- **Offset：** 消息在分区内的位置。
- **Producer：** 选择 Topic/Partition 写入。
- **Consumer Group：** 同组内一个 Partition 同时只分配给一个消费者实例。
- **Replica / Leader / Follower / ISR：** 分区副本与同步集合，用于容错。

Kafka 只保证**单分区内有序**。同一业务聚合要使用稳定 Key，使其进入同一分区；跨分区不存在全局顺序。

## 2. Producer 可靠性配置

FitPilot 配置：

- `acks=all`：Leader 等待 ISR 中要求的副本确认。
- `enable.idempotence=true`：Producer 使用 PID 和序列号抑制重试导致的分区内重复写。
- `max.in.flight.requests.per.connection=5`：与幂等 Producer 配合，在并发请求下保持顺序保证。
- Key 使用 `workoutId`、`personalRecordId` 或 `trainingPlanId`，确保同聚合事件同分区有序。

### 仍然可能发生什么

Producer 幂等只覆盖单 Producer 会话和 Kafka 写入层，不会让“数据库 + Kafka + 消费者业务”自动 Exactly Once。应用在收到 Kafka 成功后、更新 Outbox 状态前宕机，Relay 会再次发送同一事件。

## 3. Consumer、Offset 与 Rebalance

项目关闭自动提交，`ack-mode=record`，每条 Listener 成功后提交 Offset；`isolation.level=read_committed` 不读取 Kafka 事务中未提交的记录。

Consumer Group 增减实例或 Partition 变化会触发 Rebalance。Rebalance 前已处理但 Offset 未提交的消息可能重放，因此消费者必须幂等。长时间处理还可能超过 `max.poll.interval.ms` 被认为失活，应控制批次与处理时长，或调整 Poll/处理模型。

**问：Offset 提交在业务事务前还是后？** 前提交可能丢业务，后提交可能重复业务。项目选择业务事务成功后再确认，接受重复并通过 Inbox/唯一约束消除副作用。

## 4. 为什么需要 Transactional Outbox

### 双写问题

```text
方案 A：提交 DB → 发 Kafka
风险：DB 成功后进程宕机，消息没发。

方案 B：发 Kafka → 提交 DB
风险：消息已被消费，但 DB 最后回滚。
```

数据库本地事务不能直接覆盖 Kafka。两阶段提交理论上能协调，但复杂、耦合且可用性代价高。

### FitPilot 方案

Workout 完成事务中同时：

1. 校验状态与有效 Set。
2. 更新 Workout 为 COMPLETED。
3. 插入包含统一 Event Envelope 的 `outbox_event`。
4. 提交 PostgreSQL 事务。

Relay 每 500ms 扫描并抢占最多 100 条事件，`FOR UPDATE SKIP LOCKED` 支持多实例并发。发送成功标记 SENT；失败记录次数、下次重试时间和错误，最多 20 次。

### 为什么是 At-least-once

Relay 发送成功后才能标记 SENT。若发送成功、标记前宕机，事件仍是 SENDING/PENDING，超时后会再次发送。系统宁愿重复，也不静默丢失。

## 5. Inbox 与业务幂等

消费者在同一数据库事务中：

1. 尝试插入 `(event_id, consumer)` 到 `processed_event`。
2. 若已存在，说明该消费者处理过，直接返回。
3. 执行业务投影。
4. 提交业务变化和 Inbox 记录。

第二层防线是业务唯一约束：

- PR：`(workout_set_id, record_type)` 唯一。
- Analytics：按 Workout 投影主键唯一。
- Notification：`source_event_id` 唯一。

Inbox 防事件重复，业务唯一约束防代码缺陷、旁路写和竞争条件。两者结合比单靠 Redis 去重可靠，因为它们与业务数据处于同一个 PostgreSQL 事务。

## 6. DLT、数据库死信与回放

消费失败先按配置重试 3 次；耗尽后：

- 投递原 Topic 的 `.DLT`。
- 写入 `dead_letter_event`，保存 Event Envelope、错误和状态。
- Operations API 可查询和回放。

回放前先修复代码或脏数据。回放仍经过 Inbox 幂等判断，不能通过删除 `processed_event` 强行制造重复副作用。

### 为什么同时要 Kafka DLT 和数据库死信

Kafka DLT 适合流式运维和独立消费者；数据库死信便于业务查询、权限控制、审计与人工操作。两者需要共享 Event ID，避免互相成为不一致的事实源。

## 7. 事件契约设计

统一信封字段：

```text
eventId, eventType, eventVersion,
aggregateType, aggregateId,
occurredAt, traceId, payload
```

### 演进规则

- 优先增加可选字段，消费者对未知字段宽容。
- 不改变旧字段含义。
- 破坏性变更提升 `eventVersion`，新旧消费者并行过渡。
- Event ID 不变，重放和跨系统追踪可关联。
- 不把整个数据库实体直接序列化为事件，避免泄漏字段和强耦合。

可演进到 Schema Registry + Avro/Protobuf，以机器方式检查兼容性；当前 JSON + 显式版本在项目规模下更轻量。

## 8. 分区数与消费并发

一个 Consumer Group 的最大有效并发不超过 Partition 数。项目消费者并发为 3；若 Topic 只有 1 个 Partition，设置 3 没有吞吐收益。

扩容前先看：生产速率、消费速率、Lag、单消息处理耗时、分区热点、数据库写能力。盲目增加 Partition 会改变 Key 到 Partition 的映射，并增加文件、复制和 Rebalance 成本。

## 9. Kafka 故障时系统表现

- Workout 事务继续成功，Outbox 保持待发送。
- PR、Analytics、Notification 暂时落后。
- Kafka 恢复后 Relay 补发，消费者追平。
- 监控 `oldest pending seconds`、Pending 数、发送失败、Consumer Lag 和开放死信。
- 若积压持续扩大，按 Runbook 判断 Broker、网络、毒消息或消费者/数据库瓶颈。

这是核心业务可用性与派生数据实时性之间的权衡。

## 10. 常见替代方案

### 直接同步调用

实现简单、强交互结果立即返回，但延长核心事务、放大故障耦合，调用方失败恢复困难。适合必须同步得到结果的能力，不适合 PR/分析/通知投影。

### Debezium CDC Outbox

从数据库 WAL 捕获 Outbox 变化，减少应用轮询和发送逻辑，吞吐和解耦更好；代价是引入 Connect、Offset、Schema 和运维复杂度。当前轮询规模足够且更容易调试，后续积压和数据库扫描成为瓶颈时再演进。

### Kafka Transactions / EOS

适合 Kafka → 处理 → Kafka 的原子链路；当最终副作用写 PostgreSQL 时，仍需要数据库幂等或协调机制。不能因为启用 Kafka 事务就宣称整个业务 Exactly Once。

## 11. 高频追问

**问：Kafka 为什么吞吐高？** 顺序追加日志、页缓存、批量、压缩、零拷贝能力、分区并行和避免随机磁盘访问共同作用，不是仅因为“写磁盘快”。

**问：Kafka 会丢消息吗？** 配置不当、未等待 ACK、副本不足、错误清理策略或应用错误都可能丢。要结合 `acks=all`、ISR/副本、Producer 重试、Outbox、消费后提交和监控形成端到端保证。

**问：如何保证顺序？** 同一聚合使用相同 Key 进入同一 Partition；消费者对该 Partition 串行处理。若失败消息跳过进入 DLT，后续消息可能越过它，必须明确业务是否允许。

**问：消息积压怎么办？** 先确认生产/消费速率和瓶颈；可增加 Consumer 实例但不超过 Partition，有必要再扩 Partition；优化单条处理、批量写库和索引；对毒消息隔离；扩容前确认数据库能承受。

**问：为什么不用 Redis 做消息队列？** Redis Stream 可以做队列，但 Kafka 在持久日志、分区扩展、保留回放、消费者生态和大规模吞吐上更合适；Redis 在本项目是可降级缓存层，不承担事件真源。

## 12. 本章一段话总结

> FitPilot 以 PostgreSQL Outbox 解决业务库与 Kafka 原子性缺口，Relay 通过 SKIP LOCKED 多实例抢占并用相同聚合 Key 保序。发送成功但标记前宕机会重复，因此明确采用 At-least-once；消费者在业务事务中写 Inbox，并用业务唯一约束做第二层幂等。失败进入 Kafka DLT 和数据库死信，可审计回放。Kafka 故障只延迟派生数据，不回滚 Workout 核心事实。

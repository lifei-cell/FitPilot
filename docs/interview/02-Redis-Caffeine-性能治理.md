# Redis、Caffeine 与性能治理

## 1. Redis 基础原理

Redis 的核心执行模型是命令处理主线程串行执行，大多数命令无需多线程锁，因此单次操作延迟低；网络读取、持久化和部分后台任务可以由其他线程承担。“单线程”不等于整个 Redis 进程只有一个线程，也不等于复杂命令不会阻塞。

常见数据结构：

- String：缓存值、计数、分布式锁、幂等状态。
- Hash：对象字段、Token Bucket 的 `tokens/last_refill`。
- List：Agent 最近消息队列。
- Set：去重集合。
- ZSet：排行榜、延迟队列候选方案。
- Bitmap/HyperLogLog：签到、近似去重统计。
- Stream：可消费的日志结构，但 FitPilot 事件主链使用 Kafka。

Redis 适合高性能临时状态，不应默认成为不可恢复业务事实的唯一存储。FitPilot 中 PostgreSQL 保存训练、Agent 消息和事件真源，Redis 负责加速、协调和短期会话。

## 2. 持久化、高可用与数据丢失边界

- **RDB：** 周期快照，文件紧凑、恢复快；两次快照之间可能丢数据。
- **AOF：** 记录写命令，通常数据丢失窗口更小；文件更大，需要重写。
- **混合持久化：** 结合 RDB 基线与 AOF 增量。
- **主从复制：** 异步复制为主，主节点确认后从节点可能还没收到，故障切换仍可能丢最后一小段写入。
- **Sentinel：** 监控、选主和故障转移。
- **Cluster：** 16384 Hash Slot 分片，提高容量和吞吐；多 Key 操作需要同 Slot，可通过 Hash Tag 控制。

项目没有把 Redis 当作强一致数据库，因此其短暂不可用时允许缓存回源或局部降级，而不是让训练核心链路停摆。

## 3. Caffeine + Redis 二级缓存

### 读取链路

```text
Request → Caffeine L1 → Redis L2 → 分布式重建锁 → PostgreSQL
```

- L1 位于单 JVM，延迟最低，但多实例之间不共享。
- L2 由 Redis 共享，减少数据库压力，但有网络和序列化成本。
- PostgreSQL 是最终真源。

`TwoLevelCache` 使用 Caffeine `maximumSize=10000`、L1 TTL 300 秒；Redis L2 TTL 1800 秒并增加最多 300 秒随机抖动。

### 缓存穿透、击穿、雪崩

**穿透：** 查询不存在的 Key，缓存永远未命中。项目将空结果编码为短期缓存标记 `__FITPILOT_NULL__`。扩展方案是 Bloom Filter，但 Bloom Filter 有误判且删除困难，不适合所有场景。

**击穿：** 热点 Key 过期，大量请求同时回源。项目用 Redis `SET NX EX` 抢重建锁，未抢到者有限等待并二次读取；等待后仍无值可直接回源，避免无限阻塞。

**雪崩：** 大量 Key 同时过期或 Redis 整体故障。项目对 L2 TTL 加随机抖动，并在 Redis 异常时回源数据库。

### Cache Aside 写一致性

项目先提交数据库事务，再通过 `afterCommit` 删除 L1/L2：

```text
Update DB → Commit → Evict Cache
```

若事务回滚，不会误删缓存。删除而不是直接更新缓存，可避免维护两套对象组装逻辑。

### 一致性边界

- 多实例 L1 不能被单个实例的本地删除立即广播，因此在 TTL 窗口内可能读旧值。
- 数据库提交成功而删缓存失败，也会短暂读旧值。
- Cache Aside 不是强一致。

可演进方案：Redis Pub/Sub 或 Kafka 广播 L1 失效、CDC 驱动失效、版本号缓存、缩短关键数据 L1 TTL。选择前要评估一致性需求，不要机械“双删”。

### 高频追问

**问：为什么先更新数据库再删缓存？** 先删缓存后数据库更新期间，另一请求可能把旧值重新写回缓存。更新 DB 后删除更容易收敛；仍存在删除失败窗口，需要重试、消息或 TTL 兜底。

**问：为什么不用写穿/写回缓存？** 写穿增加写链路依赖，写回可能丢数据且一致性复杂。这里数据库是真源、读热点明显，Cache Aside 更合适。

## 4. Caffeine 原理与注意点

Caffeine 是进程内高性能缓存，基于 Window TinyLFU 等准入/淘汰思想，在频率和近期访问之间平衡。它没有跨实例一致性，也会占用 JVM Heap。

项目限制最大条目数并记录命中率、Miss、Eviction 和估算大小。面试时要说明：缓存命中率高不代表设计一定好，若数据陈旧、Key 基数失控或对象过大，命中率反而掩盖风险。

可关注指标：命中率、Load 延迟、Eviction、对象大小、GC、热点分布、数据库回源 QPS。

## 5. Redis 分布式锁

### 项目实现

加锁：`SET key randomToken NX EX ttl`。解锁使用 Lua：只有 Value 等于持有者 Token 才删除，避免线程 A 锁过期后误删线程 B 新获得的锁。

### 为什么必须有随机 Token

如果只 `DEL key`，持有者业务执行超过 TTL 后锁已被别人获得，旧持有者完成时会删除新锁。唯一 Token 将锁与持有者绑定。

### 边界

- 锁 TTL 到期但业务尚未结束，临界区可能并发执行。
- Redis 主从异步复制与故障切换可能造成锁状态丢失。
- 该锁用于缓存重建抑制，不用于资金或不可重复核心写入；即使锁失效，最多重复查库，业务正确性不依赖它。

若核心业务必须互斥，应优先使用数据库约束/行锁、可续期租约、Fencing Token，或设计天然幂等。不要把 Redlock 当作所有分布式锁问题的万能答案。

## 6. Lua Token Bucket 限流

### 算法原理

令牌桶以固定速率补充 Token，请求消耗 Token：

```text
newTokens = min(capacity, oldTokens + elapsed × refillRate)
allowed = newTokens >= requested
```

它允许容量范围内的短突发，长期速率受 refillRate 约束。漏桶更强调平滑输出；固定窗口简单但边界时刻可能双倍突发；滑动窗口更精确但存储和计算更高。

### FitPilot 落地

- API：容量 400，补充 200/s。
- 登录：容量 10，补充 5/s。
- Redis Hash 保存 Token 和上次补充时间。
- Lua 在 Redis 内原子完成读取、计算、扣减和设置 TTL，避免客户端多次命令竞态。
- Redis 故障时降级为单 JVM Caffeine 内的本地 Token Bucket，并在响应/指标中标识 degraded。

### 边界与扩展

本地降级只能保证单实例配额，多实例总流量会放大。生产可根据风险选择 Fail-open、Fail-closed 或本地保守配额；登录和高风险接口通常应更保守。

**追问：为什么 Lua 原子？** Redis 单条命令原子，但“读 Token → 计算 → 写 Token”是多步；Lua 让整段脚本作为一个不可被其他命令插入的执行单元。

## 7. HTTP 幂等

### 概念

幂等表示同一操作执行一次或多次，对最终业务状态的影响相同。GET/PUT/DELETE 在 HTTP 语义上通常应幂等，但具体实现仍可能不幂等；POST 也可以通过业务设计实现幂等。

### 项目实现

客户端对写请求携带 `Idempotency-Key`。Filter 将“方法、URI、用户身份、Key”做 SHA-256 形成 Redis Key：

- 首个请求用 Lua 原子写入 `P`（处理中），TTL 30 秒。
- 并发相同请求看到 `P` 返回 409。
- 2xx 响应保存状态码、Content-Type 和 Body，TTL 24 小时。
- 后续相同请求直接回放，响应头标记 `Idempotency-Replayed=true`。
- 失败响应删除占位，允许客户端重试。
- Redis 故障时 Fail-open，继续业务处理。

### 关键边界

- Redis Fail-open 时不能依赖此 Filter 保证核心业务唯一性，数据库约束和业务幂等仍必须存在。
- 处理超过 30 秒，占位可能过期；可根据业务延长 TTL 或使用租约续期。
- 若相同 Key 配不同请求体，当前 Scope 不含 Body Hash，可能错误回放；生产级实现应记录请求摘要并拒绝不一致请求。
- 响应缓存会占 Redis 内存，应限制 Body 大小和适用接口。

**追问：幂等和分布式锁有什么区别？** 锁控制同一时间只有一个执行者；幂等保证重复执行不产生额外业务副作用。锁会失效，最终仍要幂等。

## 8. Agent 消息热缓存

PostgreSQL `agent_message` 是真源，Redis List `agent:session:{sessionId}` 只缓存最近 30 条消息，TTL 2 小时：

- 写入先持久化 PostgreSQL，再追加 Redis List 并 `LTRIM`。
- Redis Miss/异常时从 PostgreSQL 分页读取并回填。
- 旧版本仅在 Redis 的消息可懒迁移到 PostgreSQL。
- 会话正文默认保留 180 天，缓存不承担长期记忆。

这与“把聊天历史全塞 Redis”相比，能够跨设备恢复并避免缓存淘汰造成数据丢失。

## 9. 性能分析方法

不要只报 QPS。完整性能结论至少包含：

- 场景：读写比例、数据规模、缓存冷热、并发模型。
- 吞吐：实际 req/s、Dropped Iterations。
- 延迟：P50/P95/P99/最大值。
- 正确性：HTTP 失败与业务断言失败。
- 饱和度：CPU、内存、GC、连接池、线程池、Kafka Lag、Redis/DB 延迟。
- 环境：实例数、资源限制、网络、版本。

FitPilot 1000 QPS 档实际为 998.83 req/s、P95 1.76ms、0% HTTP 失败，但有 6 次 Dropped Iterations，只能说“该本机热点 GET 场景接近目标”，不能说系统稳定支撑 1000 QPS。

## 10. 本章一段话总结

> FitPilot 将 Redis 定位为可丢失、可降级的加速与协调层：Caffeine + Redis 做二级缓存，Cache Aside 在事务提交后失效；随机 TTL、空值和重建锁分别治理雪崩、穿透和击穿；Lua Token Bucket 实现跨实例原子限流；Idempotency-Key 回放成功响应；Agent 消息仍以 PostgreSQL 为真源。任何 Redis 机制都不替代数据库不变量。

# V1 高性能后端设计

## 数据路径

```text
Client
  ├─ Idempotency Filter（Redis 原子占位/响应回放）
  ├─ Token Bucket Interceptor（Redis Lua）
  └─ Application Service
       └─ Caffeine L1 → Redis L2 → PostgreSQL
```

二级缓存只承载读多写少或用户热点对象，当前包括动作详情、用户资料和 ACTIVE 训练计划。分页和高基数动态查询不进入 L1，避免缓存污染和 BigKey。

## Key 约定

| 能力 | Key |
|---|---|
| 二级缓存 | `fitpilot:v1:cache:{namespace}:{id}` |
| 缓存重建锁 | `fitpilot:v1:lock:cache:{namespace}:{id}` |
| 限流 | `fitpilot:v1:rate-limit:{route}:{identity}` |
| 幂等 | `fitpilot:v2:idempotency:{sha256(userId + method + canonicalPath + key)}` |
| PR 排行榜 | `fitpilot:v1:pr:leaderboard:{exerciseId}:{recordType}` |

所有 Redis 数据均有 TTL。锁值使用随机 token，并由 Lua compare-and-delete，避免误删其他实例后来获得的锁。

幂等状态保存 `method + canonicalPath + sortedQuery + SHA-256(body)` 请求指纹；同一用户在同一路由复用相同
`Idempotency-Key` 但查询参数或请求体不同时返回 `50004` 冲突。成功响应仅在不超过
`IDEMPOTENCY_MAX_CACHED_RESPONSE_BYTES` 时进入 Redis，避免大响应放大内存。创建 Workout 还会将幂等键与请求指纹
写入 PostgreSQL 并建立用户级唯一索引，因此 Redis 数据丢失后仍可返回原业务结果。
Workout 创建还会短暂锁定用户行，将 Redis 故障期间的同用户并发创建串行化，避免“先查询、后插入”的竞态窗口。

## 缓存治理

- 穿透：不存在对象写入短期空值缓存。
- 击穿：Caffeine 原子单飞 + Redis 分布式重建锁 + 有限退避读取。
- 雪崩：Redis 基础 TTL 增加随机抖动。
- 热点 Key：进程内 Caffeine 吸收高频访问，限制最大条目数。
- 一致性：数据库事务提交后删除 L1/L2；读取采用 Cache Aside。
- Redis 故障：缓存直接回源 PostgreSQL；限流切换到有界 Caffeine 本地桶；幂等退化为业务唯一约束和状态幂等。

## 边界

本阶段不引入 Kafka 或 Outbox。排行榜更新在事务提交后执行，Redis 数据异常时查询回源 PostgreSQL；跨服务事件一致性属于 V2 范围。

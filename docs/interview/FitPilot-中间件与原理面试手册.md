# FitPilot 中间件与原理面试手册

> 基线：FitPilot V6、Flyway V1-V15、当前仓库 `main`。本手册只解释已经在代码中落地的设计；扩展方案会明确标注为“可演进”，不得说成已经实现。

## 使用方法

面试回答不要从组件定义开始背。推荐顺序：

1. **业务问题：** 为什么会遇到这个问题。
2. **方案选择：** 为什么选这个组件或模式。
3. **项目落地：** 数据结构、关键流程和源码位置。
4. **异常路径：** 组件故障、重复请求、并发、超时如何处理。
5. **权衡边界：** 方案没有保证什么。
6. **演进方向：** 规模增长后如何升级。

一个合格回答示例：

> Workout 完成既要更新数据库又要产生 Kafka 事件，直接双写会有原子性缺口。因此我把业务状态和 Outbox 放在同一个 PostgreSQL 事务里，由 Relay 异步发送 Kafka。发送成功但标记前宕机会重复投递，所以系统明确是 At-least-once，消费者通过 Inbox 和业务唯一约束幂等。Kafka 故障不影响 Workout 完成，恢复后补发。规模扩大后可评估 Debezium CDC，但当前轮询 Relay 更简单、可控。

## 知识地图

| 专题 | 项目用途 | 最容易被追问的点 | 文档 |
|---|---|---|---|
| Java / Spring / 领域设计 | 模块化单体、事务、鉴权、核心业务 | Spring 事务失效、状态机、快照、JWT 轮换 | [01-Java-Spring-领域与数据库](01-Java-Spring-领域与数据库.md) |
| JVM / 并发 / 网络 | 线程池、调度任务、HTTP Provider 调用 | JMM、GC、线程池背压、超时、Virtual Thread | [08-Java-JVM并发与网络](08-Java-JVM并发与网络.md) |
| PostgreSQL / MyBatis / Flyway | 业务真源、锁、索引、版本迁移 | MVCC、隔离级别、行锁、部分唯一索引、乐观锁 | [01-Java-Spring-领域与数据库](01-Java-Spring-领域与数据库.md) |
| Redis / Caffeine | 二级缓存、限流、幂等、会话热缓存 | 穿透/击穿/雪崩、Cache Aside、Lua、锁误删 | [02-Redis-Caffeine-性能治理](02-Redis-Caffeine-性能治理.md) |
| Kafka / Outbox / Inbox | PR、Analytics、通知异步投影 | 消息丢失、重复、顺序、Rebalance、DLT、Exactly Once | [03-Kafka-事件一致性](03-Kafka-事件一致性.md) |
| Elasticsearch / pgvector / RAG | BM25、向量检索、融合与治理 | 倒排索引、BM25、HNSW、RRF、Chunk、Citation | [04-Elasticsearch-pgvector与RAG](04-Elasticsearch-pgvector与RAG.md) |
| Agent / LLM / MCP | Tool Workflow、安全写入、模型网关 | Prompt Injection、Tool 越权、确认协议、重试熔断 | [05-Agent-LLM-MCP与产品指标](05-Agent-LLM-MCP与产品指标.md) |
| 可观测 / 测试 / Docker / Kubernetes / CI | 质量、诊断、发布、回滚 | Metrics/Logs/Traces、探针、HPA、SBOM、Digest | [06-可观测测试与交付](06-可观测测试与交付.md) |
| 高频追问 | 临场复习 | 连续追问与短回答 | [07-高频追问速查](07-高频追问速查.md) |

## 项目架构主线

```text
React Web
   ↓ JWT / REST
Spring MVC Controller
   ↓
Application Service ── Domain Rule / Guardrail
   ↓                         ↓
Repository / MyBatis     Pending Confirmation
   ↓                         ↓
PostgreSQL ← Outbox       LLM Gateway → Primary / Fallback / Rule
   ↓            ↓
pgvector      Kafka → PR / Analytics / Notification
   ↑
RAG ← Elasticsearch BM25

Caffeine L1 → Redis L2 → PostgreSQL
Micrometer / OTel → Prometheus / Tempo / Loki / Grafana
```

## 必须守住的事实边界

- Outbox + Kafka + Inbox 是 **At-least-once + 幂等消费 + 最终一致性**，不是 Exactly Once。
- Cache Aside 提供可解释的一致性边界，不是强一致或绝对无脏读。
- 本机热点读接近 1000 req/s，不代表系统稳定承载 1000 QPS，更不代表生产容量。
- Agent 中 LLM 只提议；身份、Tool 白名单、领域校验、Guardrail、确认和持久化均由后端控制。
- 受控数据验证指标聚合正确，不等于真实用户收益或因果结论。
- CI、GHCR 和历史 Kind 演练已有证据；真实 Production Delivery Gate 仍是 `SKIPPED`，不得说已生产上线。

## 面试前复习节奏

- **第一遍：** 能用 3 分钟讲清整条架构主线。
- **第二遍：** 每个专题至少能回答“为什么不用更简单方案”和“组件挂了怎么办”。
- **第三遍：** 对照源码把类名、表名、关键索引和配置说准确。
- **第四遍：** 使用速查题做压力测试，每题 30 秒内给出结论、落地和边界。

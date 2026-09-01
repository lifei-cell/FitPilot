# FitPilot 简历亮点与详细阐述

> 文档口径：FitPilot V6；当前功能验收基线为 `6a646299c81cbfc0fcd5f8f99e750226cb4642eb`（2026-09-01），数据库迁移为 Flyway V1-V14。远端发布和真实生产状态按各自 revision 单独说明，不与当前本地验收混用。

## 一、简历可直接使用版本

### FitPilot——AI Native 健身训练平台

**项目周期：** 2026.08—至今<br>
**项目简介：** 面向健身用户提供训练计划、Workout 记录、个人纪录（PR）、训练分析、专业知识检索和 AI 辅助调节计划等能力，并围绕高并发读、事件最终一致性、跨设备会话、RAG 内容治理和 Agent 安全写入完成工程化设计。<br>
**技术栈：** Java 21、Spring Boot 3.5、Spring Security、MyBatis-Plus、PostgreSQL/pgvector、Redis、Caffeine、Kafka、Elasticsearch、Flyway、OpenTelemetry、Prometheus、Grafana、Loki、Tempo、Docker Compose、Kubernetes、Testcontainers

**主要工作：**

- **训练领域建模与历史快照：** 将可持续修改的 `TrainingPlan` 与不可变的训练事实 `Workout` 分离，开始训练时复制 `WorkoutExercise` 快照，避免计划调整污染历史记录、PR 计算和训练分析。
- **核心写链路一致性与安全：** 以事务和状态机编排 Workout 完成流程，通过行锁分配 Set 序号、唯一约束兜底、重复完成幂等返回及 `userId + resourceId` 所有权查询，解决并发重复写、越权访问和半完成状态问题。
- **事件驱动与可靠消费：** 采用 Transactional Outbox 将 Workout 状态与领域事件原子提交，经 Kafka 异步更新 PR、Analytics 和通知；结合 Inbox、业务唯一约束、重试、DLT、数据库死信与人工回放，实现 At-least-once 语义下的幂等消费和故障恢复。
- **缓存与流量治理：** 基于 Caffeine + Redis 构建二级缓存，使用空值缓存、TTL 抖动和分布式重建锁治理穿透、雪崩与击穿；Redis 故障时自动回源数据库，并将 Lua Token Bucket 降级为进程内限流，保障核心业务可用。
- **Hybrid RAG 与内容治理：** 实现 Parent-Child Chunk、pgvector HNSW 与 Elasticsearch BM25 双路召回，通过 RRF 和确定性 Rerank 返回父级上下文与 Citation；V14 增加来源可信等级、不可变版本、删除传播、用户反馈审核和动态评测集，ES 异常时可降级并重建。
- **安全可控的单 Agent Workflow：** 将 LLM 限定为结构化提议组件，后端控制 JWT 身份、Tool 白名单、Owner 校验、领域规则、Guardrail、一次性确认和最终持久化；V12 以 PostgreSQL 持久化会话并支持跨设备恢复，V13 基于最近 28 天训练事实生成可解释调整，确认后只创建新 DRAFT。
- **可观测与工程验证：** 建立 Metrics、Logs、Traces、告警与 LLM Token/费用审计；当前 V6 基线完成 46 个后端零跳过测试、Flyway V1-V14 和前端 14 个组件测试、3 个浏览器场景；历史 V5 本机 Compose 压测完成 118,980 次混合流量请求，业务成功率 99.99%、普通 API P95 10.03ms、Agent P95 30.70ms。

> 若版面只能保留 5 条：Java 后端岗位保留第 1、2、3、4、7 条；AI/Agent 岗位保留第 1、3、5、6、7 条。

## 二、详细亮点阐述

### 1. 用快照隔离“可变计划”与“历史事实”

**业务问题：** 训练计划会持续调整。如果 Workout 只保存计划动作 ID，用户修改动作、组数或目标次数后，历史训练内容会随之变化，进一步导致 PR 和分析结果无法追溯。

**方案设计：**

- `TrainingPlan` 表达未来训练意图，允许编辑和版本演进；`Workout` 表达已经发生的训练事实。
- 开始训练时，在同一事务内把计划日中的动作名称、顺序、目标组数、次数区间、RPE、休息时间和备注复制为 `WorkoutExercise` 快照。
- Workout 后续只围绕快照记录 Set，历史查询和统计不再依赖当前计划内容。

**设计价值：** 这是数据语义隔离，而非简单字段冗余；它用少量存储换取历史一致性、审计可追溯性和统计口径稳定性。

### 2. 用事务、状态机和数据库约束守住 Workout 完成链路

**业务问题：** 完成训练会同时修改 Workout 状态、校验有效 Set 并产生后续事件；重复请求、并发加组或跨用户访问都可能造成脏数据。

**方案设计：**

- 使用 `IN_PROGRESS → COMPLETED/CANCELLED` 状态机限制非法流转，完成前至少存在一个有效 Set。
- 完成操作使用事务；已完成 Workout 再次提交时直接返回已有结果，实现业务幂等。
- Set 编号由数据库行锁串行分配，并通过唯一索引处理极端竞争。
- 私有资源统一按 `userId + resourceId` 查询，接口不信任客户端传入的用户身份，降低 IDOR 风险。
- 通用写请求支持 `Idempotency-Key`，由 Redis 原子占位并回放成功响应。

**权衡：** 行锁只覆盖同一 WorkoutExercise 的短临界区，避免使用粗粒度全局锁；最终一致性任务不塞进核心完成事务，缩短锁持有时间。

### 3. Transactional Outbox 解决数据库与 Kafka 双写一致性

**业务问题：** 若先更新 Workout 再发 Kafka，应用可能在两步之间宕机；若先发消息再提交数据库，消费者可能读到尚未生效的数据。

**方案设计：**

- 在完成 Workout 的数据库事务中同时写入 Outbox，保证业务状态和“待发送事件”原子落库。
- Relay 批量抢占待发送事件并投递 Kafka，失败按次数和时间退避重试，超过阈值进入死信。
- PR、Analytics、Notification 消费者以事件 ID 写 Inbox，并结合 PR 来源等业务唯一约束防止重复副作用。
- 提供 Outbox 状态、死信查询和人工回放接口，Kafka 恢复后可继续补发。

**准确边界：** 系统实现的是 `At-least-once + 幂等消费 + 最终一致性`，不能描述为 Exactly Once。Kafka 不可用时已完成的 Workout 不回滚，派生数据在恢复后追平。

### 4. 二级缓存兼顾性能、故障降级与一致性边界

**业务问题：** 动作详情和 ACTIVE 计划属于热点读取；单纯依赖 Redis 会增加网络开销，并把 Redis 变成核心链路的单点依赖。

**方案设计：**

- 读取依次经过 Caffeine L1、Redis L2、PostgreSQL，L1 设置容量和 TTL，L2 TTL 加随机抖动。
- 对不存在的数据写短 TTL 空值，减少缓存穿透；未命中时使用 Redis 分布式重建锁和有限等待抑制热点 Key 击穿。
- 用户资料和 ACTIVE 计划在数据库事务提交后删除缓存，采用 Cache Aside，避免事务回滚但缓存已失效的时序问题。
- Redis 异常时回源数据库；分布式限流降级为本地 Token Bucket，核心业务继续服务但不承诺跨实例精确配额。

**验证结果：** 单机热点 GET 在 100/500 QPS 档无失败、无调度丢弃；1000 QPS 档达到 998.83 req/s、P95 1.76ms，但有 6 次 dropped iterations，因此只表述为“接近 1000 QPS 热点读目标”，不写成“稳定无损支撑 1000 QPS”。

### 5. Hybrid RAG 提升专业知识召回与引用可追溯性

**业务问题：** 仅关键词检索难以覆盖同义表达，仅向量检索又可能弱化精确术语；直接把碎片交给模型还会丢失上下文和来源。

**方案设计：**

- Markdown/Text 文档先按标题和段落生成 Parent Chunk，再生成适合检索的 Child Chunk。
- PostgreSQL 保存文档、Chunk、Embedding 和索引状态，作为知识真源；pgvector 使用 384 维 HNSW cosine 索引。
- Elasticsearch BM25 与 pgvector 向量检索并行召回，使用 RRF 融合，再根据词项覆盖和短语命中做确定性二次排序。
- 命中 Child 后回填 Parent Context，并返回来源 URL、许可证和 Citation，模型不能自行编造引用。
- Elasticsearch 只作为可重建索引；不可用时自动降级为向量检索，索引失败进入 `FAILED` 并由任务重试。
- 知识文档记录发布方、固定可信等级、有效期和不可变版本链；撤销或待删除内容在 PostgreSQL 与 Elasticsearch 双侧过滤，删除传播由持久化任务重试。
- 检索反馈按用户 Upsert，只有人工审核且具有正确来源的反馈才能进入版本化动态评测集，不直接污染在线排序。

**评测体系：** 建立 50 条 RAG 真值集，以 Recall@5、MRR 和引用有效率作为门禁；评测任务异步执行，并在 `SUCCEEDED` 后清理临时评测文档，避免污染后续检索结果。

### 6. Agent 写操作采用“模型提议、后端决策、用户确认”

**业务问题：** 健身计划生成属于有副作用的写操作，不能让模型直接拼接数据库参数或根据对话中的伪造身份执行写入。

**方案设计：**

- Agent 使用单 Workflow：意图识别 → Owner-scoped 只读 Tools → 结构化计划 → Schema/领域校验 → Guardrail → Pending Action → 用户确认 → 创建 DRAFT 计划。
- Tool 参数不接受 `userId`，用户身份统一继承 JWT；只开放领域 Tool，不提供任意 SQL、Shell 等高风险能力。
- Guardrail 校验动作数量、训练日数量、组数、次数和 RPE 等边界；未经确认保持零写入。
- 确认令牌只存 SHA-256，设置有效期并保证一次性消费，过期、重放和跨用户确认均拒绝。
- PostgreSQL 保存会话与消息真源并默认保留 180 天，Redis 只缓存最近 30 条消息、TTL 2 小时；缓存不可用时回源数据库，支持跨设备历史、归档和待确认动作恢复。
- 完成训练后可提交疲劳、疼痛和备注；调整服务基于最近 28 天的完成率、RPE、容量及 PR 趋势生成证据快照，数据不足或疼痛风险会阻断草案，确认后只创建新的 DRAFT，不覆盖 ACTIVE 计划。
- 长期偏好、每次执行和 Tool 调用分别持久化与审计。

**LLM 可靠性：** LLM Gateway 统一管理 Prompt 版本、模型路由、有限重试、主备切换、Token/费用/时延审计；双模型失败后使用规则 Workflow，降级不放宽写入安全链。

**评测体系：** 建立 150 条中文 Agent 用例，围绕 Tool Selection、Task Success、规则违规和 Tool 幻觉设置门禁，而非只评估回答是否自然。

### 7. 从“功能可用”推进到“可验证、可观测、可恢复”

**工程建设：**

- 使用 Flyway V1-V14 管理数据库演进，以 Testcontainers 覆盖 PostgreSQL/pgvector、Redis、Kafka、Elasticsearch 和 Mock OpenAI-compatible 端到端链路。
- Maven 门禁包含单元测试、集成测试跳过检查、JaCoCo、CycloneDX SBOM；生产镜像使用非 root 用户运行。
- 通过 Micrometer + OpenTelemetry 打通 Prometheus、Grafana、Loki、Tempo、Alertmanager，预置 API、Agent、Kafka、RAG、LLM Cost 等 Dashboard 与告警。
- 完成 Redis、Kafka、Elasticsearch、主备 LLM 故障演练，以及 App 下线触发告警、服务恢复和告警解除验证。

**本机 P0 验证结果：**

| 场景 | HTTP 请求 | HTTP 失败率 | 业务成功率 | 普通 API P95 | Agent P95 | Dropped |
|---|---:|---:|---:|---:|---:|---:|
| 30 分钟混合流量 | 118,980 | 0.0034% | 99.99% | 10.03ms | 30.70ms | N/A |
| 5 分钟突发流量 | 65,014 | 0.0138% | 99.98% | 10.39ms | 34.65ms | 0 |

资源观测峰值为 App CPU 188.1%（多核口径）、内存占比 32.75%、Hikari Active 4/10、Kafka Consumer Lag 0、Outbox 最老 1 秒、开放死信 0。少量 HTTP 失败来自本机 k6 到 Docker Desktop 主机桥接的 I/O timeout，应用无 ERROR 日志和重启。

## 三、证据边界与面试表述注意事项

- 当前功能验收基线 `6a646299c81cbfc0fcd5f8f99e750226cb4642eb` 已执行 `mvn verify`：22 个 Surefire/Failsafe 报告、46 个测试，失败 0、错误 0、跳过 0；Testcontainers 实际启动 PostgreSQL/pgvector、Redis、Kafka、Elasticsearch，Flyway V1-V14 和 JaCoCo 门禁通过。
- 同一功能基线的 Web 已通过 ESLint、TypeScript、14 个 Vitest/RTL 测试、生产构建和 3 个 Playwright Chromium 场景；这些数量代表已执行用例，不等同于前端所有业务页面已获得充分覆盖。
- 30 分钟混合与 5 分钟突发结果来自本机 Docker Compose 环境，不能外推为真实生产容量或多节点扩展结论。
- GitHub CI、安全扫描、GHCR 多架构镜像、Digest、SBOM 和 provenance 已对 revision `1d98621891ff92d98ad57c77ff212015b641681f` 验证通过；该远端证据早于当前功能基线，不能写成 `6a64629` 已发布。
- 同一旧 revision 的一次性 Kind 演练已通过，但真实 Kubernetes Production Delivery Gate 明确为 `SKIPPED`；不能写“已生产上线”“生产发布验证通过”。
- 不使用“Exactly Once”“绝对一致”“零故障”“稳定支撑 1000 QPS”等超出证据范围的表述。

## 四、证据索引

| 结论 | revision / Run | 原始报告 |
|---|---|---|
| V6、Flyway V1-V14、46 个后端零跳过测试、Web 14 个组件测试与 3 个浏览器场景 | `6a646299c81cbfc0fcd5f8f99e750226cb4642eb` | [P1 AI 产品价值验收](../release/p1-ai-product-validation.md) |
| 30 分钟混合流量、5 分钟突发流量、告警恢复演练 | Run ID `20260830-091822`，历史 V5/V9 基线 | [V5 性能验证](../performance/v5-production-validation.md)、[P0 生产验收](../release/p0-production-validation.md) |
| CI、安全扫描、GHCR 多架构镜像、Digest、SBOM、provenance | `1d98621891ff92d98ad57c77ff212015b641681f`；CI Run `33352127749`；Release Run `33352537058` | [P1 远端发布验收](../release/p1-delivery-validation.md) |
| Kind 交付演练 `PASS`；真实 Production Delivery Gate `SKIPPED` | `1d98621891ff92d98ad57c77ff212015b641681f` | [P1 远端发布验收](../release/p1-delivery-validation.md#production-delivery-gateskipped) |

## 五、面试时的 30 秒项目总结

FitPilot 是我基于 Java 21 和 Spring Boot 3.5 实现的 AI Native 健身训练平台。我先围绕训练计划、Workout 快照、Set、PR 和 Analytics 建立业务闭环，再用 Transactional Outbox + Kafka 保证派生数据最终一致，用 Caffeine + Redis 优化热点读。AI 部分实现了带内容治理与反馈评测的 Hybrid RAG，以及必须经过领域校验、Guardrail 和用户确认才能写入的单 Agent Workflow；会话以 PostgreSQL 为真源，并能根据训练反馈生成可解释的计划调整。当前 V6 基线已通过 46 个后端零跳过测试和前端 14 个组件测试、3 个浏览器场景；远端发布证据属于较早 revision，真实生产门禁仍为 `SKIPPED`。

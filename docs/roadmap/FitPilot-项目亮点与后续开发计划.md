# FitPilot 项目亮点与后续开发计划

> 梳理基线：2026-09-01，FitPilot V6，功能验收 revision `6a646299c81cbfc0fcd5f8f99e750226cb4642eb`，数据库迁移 Flyway V1-V14。远端 CI/Release 证据仅绑定 `1d98621891ff92d98ad57c77ff212015b641681f`，真实 Production Delivery Gate 为 `SKIPPED`。

## 1. 项目定位

FitPilot 是一个 Java 21 + Spring Boot 3.5 + React 19 的 AI Native 健身训练平台。项目以模块化单体承载训练计划、Workout、PR、训练分析等核心业务，以 Transactional Outbox、二级缓存、Hybrid RAG 和受控单 Agent Workflow 解决一致性、性能、知识检索和 AI 安全写入问题，并补齐可观测、测试、供应链和 Kubernetes 交付门禁。

当前最有价值的特征不是技术组件数量，而是形成了“训练业务闭环 + AI 辅助决策 + 后端最终授权 + 可验证交付”的完整链路。

## 2. 已落地项目亮点

| 亮点 | 关键设计 | 业务与工程价值 | 主要证据 |
|---|---|---|---|
| 训练领域建模与历史快照 | 将可变的 `TrainingPlan` 与事实型 `Workout` 分离，开始训练时复制动作、目标组次、RPE、休息时间等快照 | 计划调整不会污染历史训练、PR 和分析口径 | `plan`、`workout` 模块，Flyway V1 |
| 核心写链路一致性 | Workout 状态机、事务完成、重复完成幂等、Set 行锁分配与唯一约束；数据库限制每个用户只能有一个进行中 Workout | 防止重复完成、并发错号、半完成状态和多活训练 | `WorkoutService`、`WorkoutRepository`、Flyway V10 |
| Owner 安全边界 | 私有资源以 `userId + resourceId` 查询，身份来自 JWT；通用写请求支持 `Idempotency-Key` | 降低 IDOR、跨用户确认和重复提交风险 | `CurrentUser`、各领域 Repository、`SecurityConfig` |
| 可靠事件驱动 | Workout 与 Outbox 同事务提交，经 Kafka 异步更新 PR、Analytics、Notification；Inbox、业务唯一约束、退避重试、DLT、死信与回放兜底 | Kafka 故障不回滚核心训练，恢复后派生数据可追平 | `infrastructure/events`、Flyway V3 |
| 二级缓存与故障降级 | Caffeine L1 + Redis L2、空值缓存、TTL 抖动、分布式重建锁；Redis 故障时回源 PostgreSQL，限流降级为本地 Token Bucket | 优化热点读，同时避免 Redis 成为核心业务单点 | `infrastructure/performance`、`RedisFailureGuard`、`TwoLevelCache` |
| Hybrid RAG 与内容治理 | Parent-Child Chunk、pgvector HNSW 与 Elasticsearch BM25 双路召回、RRF、确定性 Rerank、Parent Context 与 Citation；增加可信等级、不可变版本、删除传播、反馈审核和动态评测 | 兼顾专业术语、语义召回、来源追溯和知识生命周期治理 | `rag`、`evaluation` 模块，Flyway V4/V8-V9/V14 |
| 安全可控的单 Agent Workflow | LLM 只生成结构化提议；后端执行 Tool 白名单、Owner 校验、领域校验、Guardrail、一次性确认和最终持久化；主备模型失败后回退规则 Workflow | 实现“模型提议、后端决策、用户确认”，降级不绕过安全链 | `agent`、`llm` 模块，Flyway V5-V7/V11-V13 |
| 持久会话与训练调整闭环 | PostgreSQL 保存会话真源，Redis 缓存最近 30 条消息；基于 28 天完成率、RPE、疲劳、疼痛、容量和 PR 趋势生成调整证据，确认后只创建新 DRAFT | 支持跨设备连续对话，将 AI 从一次性生成推进到可解释、可拒绝的训练反馈闭环 | `AgentSessionStore`、`TrainingAdjustmentService`、Flyway V12-V13 |
| 完整 Web 业务入口 | React Web 覆盖登录注册、Dashboard、计划编辑、训练执行与反馈、动作库、进度、通知、个人资料、AI Coach、会话管理和 Citation 反馈 | 项目从后端 API 演进为可操作的端到端产品 | `web/src/pages`、`web/src/features`、`web/e2e` |
| 可观测与生产工程 | Metrics/Logs/Traces/Alert、LLM Token/费用审计、SBOM、密钥扫描、镜像扫描、不可变 Digest、Migration/Rollout/Rollback/密钥轮换脚本 | 将“能运行”推进到“可验证、可观测、可恢复” | `observability`、`.github/workflows`、`scripts/delivery`、`docs/runbooks` |

## 3. 当前成熟度与证据边界

### 3.1 已验证

- 当前功能验收 revision `6a646299c81cbfc0fcd5f8f99e750226cb4642eb` 已执行 `mvn verify`：22 个 Surefire/Failsafe 报告、46 个后端测试，失败 0、错误 0、跳过 0；Testcontainers 实际启动 PostgreSQL/pgvector、Redis、Kafka 和 Elasticsearch，Flyway V1-V14 全部应用成功。
- 后端 JaCoCo 总体行覆盖率门槛为 60%，关键包为 70%；Testcontainers 覆盖 PostgreSQL/pgvector、Redis、Kafka、Elasticsearch 和 Mock OpenAI-compatible 链路。
- Web 同一功能基线已通过 ESLint、TypeScript、14 个 Vitest/RTL 测试、生产构建和 3 个 Playwright Chromium 场景；跨浏览器恢复会话、Citation 反馈、Workout 反馈和计划编辑已进入浏览器验收。
- 本地 Docker Compose 已验证六个核心容器健康，以及注册/登录、Agent 会话、消息分页、RAG 检索、PostgreSQL V14、Redis 热缓存和 Kafka 业务主题。
- 已归档 P0 报告完成 30 分钟混合流量与 5 分钟突发流量验证。混合场景 118,980 次 HTTP 请求、业务成功率 99.99%、普通 API P95 10.03 ms、Agent P95 30.70 ms；该数据只代表本机 Compose 环境。
- Prometheus、Grafana、Loki、Tempo、Alertmanager 在线联调，以及应用下线告警、恢复和告警解除已有本机演练记录。
- revision `1d98621891ff92d98ad57c77ff212015b641681f` 的远端 CI、安全扫描、GHCR 多架构发布、不可变 Digest、SBOM 和 provenance 已通过；同一 revision 的一次性 Kind 集群完成 Migration、Rollout、Rollback、备份恢复和双密钥轮换演练。

### 3.2 仍需补齐

- 当前功能验收 revision `6a64629` 晚于已发布 revision `1d98621`；最新 V6/V14 能力尚无对应的远端 CI、GHCR Digest、SBOM 和 provenance 证据。
- 14 个前端组件测试和 3 个 Playwright 场景仍未覆盖 Auth 刷新/登出、完整 Workout 加组/完成、Agent 确认令牌异常和并发计划冲突等全部关键失败路径。
- 真实 Kubernetes Production Delivery Gate 按用户决定跳过；Kind 演练不能表述为生产上线，也没有真实集群 SLO、容量、恢复时间和成本证据。
- 系统实现的是 `At-least-once + 幂等消费 + 最终一致性`，不应表述为 Exactly Once；本机压测结果也不能直接外推为生产容量。

### 3.3 原始证据索引

| 证据 | revision / Run | 原始报告 |
|---|---|---|
| V6、Flyway V1-V14、46 个后端测试、14 个 Web 组件测试、3 个浏览器场景、Compose 冒烟 | `6a646299c81cbfc0fcd5f8f99e750226cb4642eb` | [P1 AI 产品价值验收](../release/p1-ai-product-validation.md) |
| 30 分钟混合流量、5 分钟突发流量、可观测告警恢复 | Run ID `20260830-091822`，历史 V5/V9 基线 | [V5 性能验证](../performance/v5-production-validation.md)、[P0 生产验收](../release/p0-production-validation.md) |
| CI、安全扫描、Release、GHCR Digest、SBOM、provenance、Kind 演练 | `1d98621891ff92d98ad57c77ff212015b641681f`；CI `33352127749`；Release `33352537058` | [P1 远端发布验收](../release/p1-delivery-validation.md) |
| 真实 Production Delivery Gate | 无已执行 revision，`SKIPPED` | [P1 远端发布验收](../release/p1-delivery-validation.md#production-delivery-gateskipped) |

## 4. 后续开发计划

### P0：交付闭环与质量补强（1-2 周）

#### 4.1 为当前 V6 revision 完成远端发布证据闭环

交付内容：

- 确认包含当前 V6/V14 能力的最新 `main` CI 全量成功，归档 Gitleaks、SBOM/Trivy 依赖扫描、镜像扫描和测试报告。
- 由通过验证的 revision 触发 Release，发布 GHCR 多架构镜像并归档不可变 Digest 清单。
- 将新 revision、镜像 Digest、SBOM、provenance 与本地 P1 功能验收建立一对一索引。

验收标准：当前 V6 revision 的 CI 与 Release 均有成功链接；镜像 revision、Digest、SBOM、provenance 与功能验收可双向追溯。

#### 4.2 提升前端关键链路测试

交付内容：

- 为 Auth 刷新/登出、Workout 开始/加组/完成、AI 会话恢复/待确认动作、计划版本冲突补齐 RTL + MSW 测试。
- Playwright 增加“完整 Workout 闭环”“AI 生成计划并确认”“Token 过期自动刷新”“并发编辑冲突”四条核心 E2E。
- 分阶段将前端总体行覆盖率提升到至少 60%，`api`、`auth`、`agent`、`features/workout` 关键目录达到至少 80%，并保持零跳过。

验收标准：`npm run quality` 全部通过；覆盖率阈值写入配置；关键失败场景有断言，不只验证页面能打开。

#### 4.3 真实生产交付（有生产凭据时执行）

交付内容：

- 在指定 Kubernetes context 执行 Migration、2 副本 Rollout、readiness、PDB/HPA/NetworkPolicy 验证、Rollback 和候选版本恢复。
- 在隔离的真实数据副本完成备份恢复、JWT/Operations Token 轮换与旧密钥撤销。
- 将生产执行证据与精确 revision、镜像 Digest 和审批记录绑定，禁止使用 Kind 结果替代。

验收标准：Production Delivery Gate 有真实成功链接；迁移、回滚、恢复、轮换均有脱敏证据，失败路径能够恢复。未提供生产凭据时保持 `SKIPPED`，不伪造闭环。

### P1：AI 产品成效与安全增强（2-4 周）

#### 4.4 建立 Agent 产品效果指标

- 统计会话留存、建议接受/拒绝率、确认转化率、规则降级率和单次成功成本。
- 将指标按 Prompt、模型和业务意图分组，避免只使用整体平均值掩盖局部退化。

验收标准：每项指标有稳定定义、Dashboard、告警边界和可回溯样本；模型切换必须通过离线评测与小流量对照。

#### 4.5 验证训练调整的真实收益

- 对比调整前后的计划完成率、疼痛反馈、训练容量和 PR 趋势，保留计划版本与建议证据。
- 增加正常、数据不足、疼痛风险、越界负荷和对抗输入样本，持续验证确定性安全规则。

验收标准：建议收益有统计窗口和对照口径；任何安全回归阻断发布；用户始终可以拒绝、回滚并查看差异。

#### 4.6 用审核反馈驱动 RAG 离线迭代

- 用审核后的动态样本比较 Chunk、召回权重和 Rerank 候选方案，不直接用在线负反馈改写排序。
- 按知识类别输出 Recall@5、MRR、Citation Validity、反馈率和删除传播延迟。

验收标准：Citation Validity 保持 100%，任一分类 Recall@5/MRR 相对最近成功基线回退超过 5pp 即阻断发布。

### P2：真实生产运营与规模化（6-12 周）

#### 4.7 SLO、容量与故障预算

- 为核心 API、Workout 完成、Agent、Outbox Lag、RAG 和 LLM Cost 定义 SLI/SLO，建立告警分级和责任手册。
- 在多实例环境执行持续容量测试、实例滚动、Kafka/Redis/ES 抖动与数据库连接耗尽演练。
- 根据观测数据调优连接池、线程池、缓存容量和消费者并发，不用单机短压测直接决定生产配置。

验收标准：连续观察窗口满足 SLO；告警可执行且不过度噪声；容量结论包含饱和点、恢复时间和成本。

#### 4.8 保持模块化单体，按证据演进架构

- 继续强化模块边界、领域事件契约和架构测试，避免 Controller/Service 跨模块直接访问 Mapper。
- 只有当独立扩缩容、故障隔离或团队发布节奏出现明确瓶颈时，再评估拆分事件、RAG/LLM 或通知模块。
- 不把 Multi-Agent 或微服务化作为版本目标；只有单 Workflow 无法满足可衡量的业务场景时才引入额外复杂度。

验收标准：模块依赖可自动检查；架构变更必须绑定性能、可靠性或组织收益，并给出迁移和回滚方案。

## 5. 推荐执行顺序

1. 先为当前 V6 revision 补齐远端 CI/Release/Digest 证据，结束本地与远端 revision 不一致。
2. 再补前端关键链路测试，守住已经可用的完整 Web 产品。
3. 随后量化 Agent、训练调整和 RAG 反馈带来的真实产品收益。
4. 有生产凭据时执行真实 Production Delivery Gate；最后基于 SLO、容量和成本数据决定架构扩展，避免提前微服务化或 Multi-Agent 化。

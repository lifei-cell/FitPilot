# FitPilot 项目亮点与后续开发计划

> 梳理基线：2026-08-31，`main@8c1a9ef`。本文只把当前代码、自动化报告和已归档验证记录作为“已实现”证据；配置完成但尚未在真实生产环境执行的能力单独标注。

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
| 二级缓存与故障降级 | Caffeine L1 + Redis L2、空值缓存、TTL 抖动、分布式重建锁；Redis 故障时回源 PostgreSQL，限流降级为本地 Token Bucket | 优化热点读，同时避免 Redis 成为核心业务单点 | `common/cache`、`common/idempotency`、`common/ratelimit` |
| Hybrid RAG | Parent-Child Chunk、pgvector HNSW 与 Elasticsearch BM25 双路召回、RRF 融合、确定性 Rerank、父级上下文和 Citation 回填 | 同时兼顾专业术语、语义召回、上下文完整和来源可追溯 | `rag` 模块、Flyway V4/V9 |
| 安全可控的单 Agent Workflow | LLM 只生成结构化决策；后端执行 Tool 白名单、Owner 校验、领域校验、Guardrail、一次性确认令牌和最终持久化；主备模型失败后回退规则 Workflow | 实现“模型提议、后端决策、用户确认”，降级不绕过安全链 | `agent`、`llm` 模块，Flyway V5-V7/V11 |
| 完整 Web 业务入口 | React Web 已覆盖登录注册、Dashboard、计划查看编辑、训练执行、动作库、进度、通知、个人资料与 AI Coach；AI 会话刷新后可从 Redis 短期历史恢复 | 项目从后端 API 演进为可操作的端到端产品 | `web/src/pages`、`web/src/features`、`AgentSessionStore` |
| 可观测与生产工程 | Metrics/Logs/Traces/Alert、LLM Token/费用审计、SBOM、密钥扫描、镜像扫描、不可变 Digest、Migration/Rollout/Rollback/密钥轮换脚本 | 将“能运行”推进到“可验证、可观测、可恢复” | `observability`、`.github/workflows`、`scripts/delivery`、`docs/runbooks` |

## 3. 当前成熟度与证据边界

### 3.1 已验证

- 当前本地 Surefire/Failsafe 报告包含 34 个后端测试，失败 0、错误 0、跳过 0；质量门禁禁止 `skipTests`、`skipITs` 和 `maven.test.skip`。
- 后端 JaCoCo 总体行覆盖率门槛为 60%，关键包为 70%；Testcontainers 覆盖 PostgreSQL/pgvector、Redis、Kafka、Elasticsearch 和 Mock OpenAI-compatible 链路。
- 已归档 P0 报告完成 30 分钟混合流量与 5 分钟突发流量验证。混合场景 118,980 次 HTTP 请求、业务成功率 99.99%、普通 API P95 10.03 ms、Agent P95 30.70 ms；该数据只代表本机 Compose 环境。
- Prometheus、Grafana、Loki、Tempo、Alertmanager 在线联调，以及应用下线告警、恢复和告警解除已有本机演练记录。
- 本地交付记录表明同一套脚本已在一次性 Kind 集群完成 Migration、Rollout、Rollback、备份恢复和双密钥轮换演练。

### 3.2 仍需补齐

- 前端目前只有 5 个 Vitest 测试和 1 个 Playwright E2E；当前覆盖率汇总约为行 15.82%、分支 18.07%，多数业务页面、AI 会话恢复和 Workout 编辑链路尚无单元测试保护。
- GitHub CI、Release、GHCR Digest 清单与真实 Kubernetes 生产门禁的最终远端结果仍需形成可审计证据；Kind 演练不能表述为生产上线。
- 当前机器无法连接 Docker Desktop named pipe，因此本次梳理没有重新启动 Compose 或执行浏览器回归；这是本机环境阻断，不是应用通过或失败的证据。
- AI 会话历史存放于 Redis，受 TTL 和消息上限约束。浏览器只保存用户隔离的 session ID，当前实现解决刷新恢复，但不等于跨设备、长期会话归档。
- 系统实现的是 `At-least-once + 幂等消费 + 最终一致性`，不应表述为 Exactly Once；本机压测结果也不能直接外推为生产容量。

## 4. 后续开发计划

### P0：交付闭环与质量补强（1-2 周）

#### 4.1 完成远端发布证据闭环

交付内容：

- 确认最新 `main` 的 CI 全量成功，归档 Gitleaks、SBOM/Trivy 依赖扫描、镜像扫描和测试报告。
- 由通过验证的 revision 触发 Release，发布 GHCR 多架构镜像并归档不可变 Digest 清单。
- 在指定 Kubernetes context 执行 Migration、2 副本 Rollout、readiness、PDB/HPA/NetworkPolicy 验证、Rollback 和候选版本恢复。
- 在隔离的真实数据副本完成备份恢复、JWT/Operations Token 轮换与旧密钥撤销。

验收标准：CI、Release、Production Delivery Gate 三条流水线均有成功链接；镜像 revision 与 Digest 可相互追溯；迁移、回滚、恢复、轮换均有脱敏证据，失败路径能自动恢复。

#### 4.2 提升前端关键链路测试

交付内容：

- 为 Auth 刷新/登出、Workout 开始/加组/完成、AI 会话恢复/待确认动作、计划版本冲突补齐 RTL + MSW 测试。
- Playwright 增加“完整 Workout 闭环”“AI 生成计划并确认”“Token 过期自动刷新”“并发编辑冲突”四条核心 E2E。
- 分阶段将前端总体行覆盖率从约 15.82% 提升到至少 60%，`api`、`auth`、`agent`、`features/workout` 关键目录达到至少 80%，并保持零跳过。

验收标准：`npm run quality` 全部通过；覆盖率阈值写入配置；关键失败场景有断言，不只验证页面能打开。

#### 4.3 统一版本与验收文档

交付内容：

- 将 README、V5 发布清单、P0/P1 报告中的迁移版本、测试数量和远端状态统一到当前 revision。
- 生成单一交付索引，关联源码 revision、CI Run、镜像 Digest、Kubernetes 证据和回滚结论。
- Docker Desktop 权限恢复后，重新执行 Compose 健康检查与登录、训练、AI 会话恢复三条浏览器冒烟。

验收标准：文档不再出现 V1-V9 与当前 V1-V11、29 与 34 测试等口径漂移；每项“已通过”均可定位到原始证据。

### P1：产品闭环与 AI 体验（2-6 周）

#### 4.4 Agent 长期会话与跨设备恢复

- 在 PostgreSQL 增加会话元数据与归档消息，Redis 继续承担热会话缓存，形成 DB 真源 + Redis 热数据。
- 提供会话列表、重命名、归档、删除和分页拉取；所有操作保持 Owner 校验和数据保留策略。
- Pending Action 服务端持久化状态作为唯一真源，前端本地存储只承担快速恢复提示。

验收标准：跨浏览器登录可恢复历史；Redis 清空后仍可读取已归档会话；删除和数据保留策略有集成测试。

#### 4.5 基于训练反馈的计划调节闭环

- 采集 Workout 完成率、实际 RPE、疼痛/疲劳反馈、训练容量和 PR 趋势。
- 由规则 + LLM 生成减量、进阶或动作替换建议，继续走 Guardrail、差异预览和用户确认，不允许模型直接修改 ACTIVE 计划。
- 为每次调整记录原因、输入证据、计划版本和回滚点。

验收标准：建议可解释、可拒绝、可回滚；越界负荷、伤病风险词和缺失数据均有安全策略；离线评测覆盖正常、边界和对抗样本。

#### 4.6 RAG 内容治理与反馈闭环

- 增加来源可信等级、版本更新、失效时间、重复文档检测和删除传播。
- 收集引用点击、答案有用性和错误引用反馈，形成可回放的 RAG 评测样本。
- 评测门禁按类别跟踪 Recall@5、MRR、Citation Validity，防止总体均值掩盖薄弱主题。

验收标准：文档全生命周期可审计；删除后 pgvector 与 Elasticsearch 均不可检索；每次索引或排序变更必须通过固定数据集回归。

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

1. 先完成远端发布证据闭环，明确项目当前可交付边界。
2. 再补前端关键链路测试，守住已经可用的完整 Web 产品。
3. 随后实现 Agent 长期会话与训练反馈调节，直接提升用户留存和 AI 价值。
4. 最后基于真实 SLO、容量和成本数据做架构扩展，避免提前微服务化或 Multi-Agent 化。

# FitPilot V6.0

FitPilot V6 是一个 Java 21 + Spring Boot 3 的 AI Native 健身训练产品。在完整训练业务、高性能数据路径、事件驱动、Hybrid RAG 和单 Agent Workflow 之上，V6 增加跨设备 Agent 会话、可解释训练计划调整及 RAG 治理反馈闭环；保持模块化单体，不引入 Multi-Agent。

> 当前功能验收基线：`6a646299c81cbfc0fcd5f8f99e750226cb4642eb`（2026-09-01）。版本、测试与发布结论必须分别绑定下文的本地验收报告、远端发布 revision 和 Production Delivery Gate 状态，不能跨证据层复用。

## 架构

```text
REST Controller
      ↓
Application Service（用例编排、事务、owner 校验）
      ↓
Domain（计划校验、Epley PR 计算）
      ↓
Repository → MyBatis-Plus Mapper → PostgreSQL
```

模块按 `auth / user / exercise / plan / workout / pr / analytics` 划分。训练开始时把计划动作复制为 WorkoutExercise 快照，之后修改计划不会污染历史事实。所有私有资源均使用 `userId + resourceId` 查询，避免 IDOR。

热点读链路：

```text
Request → Caffeine L1 → Redis L2 → 分布式重建锁 → PostgreSQL
```

- 空值缓存阻止缓存穿透，重建锁和有限等待抑制缓存击穿。
- L2 TTL 加随机抖动缓解缓存雪崩，L1 有容量上限和自动淘汰。
- 用户资料、ACTIVE 计划在数据库事务提交后删除缓存，采用 Cache Aside 保证一致性。
- Redis 故障时缓存回源数据库、限流降级为进程内 Token Bucket，核心业务保持可用。

训练完成事件链路：

```text
Workout + Outbox（同一事务）→ Kafka → PR / Analytics → PR Event → Notification
```

Kafka 故障不会回滚已完成的 Workout。PR、分析投影和通知采用最终一致性；恢复后 Outbox 自动补发。

RAG 检索链路：

```text
Markdown / Text → Parent-Child Chunk → Embedding → pgvector HNSW
                                             ↘ Elasticsearch BM25
Query → BM25 + Vector Search → RRF → Deterministic Rerank → Parent Context + Citation
```

PostgreSQL 保存文档、Chunk、Embedding 和索引状态，是知识库真源；Elasticsearch 只承担可重建的 BM25 索引。索引失败时文档进入 `FAILED` 并由后台任务重试，查询自动降级为 pgvector 向量检索。

Agent 写入链路：

```text
Intent → owner-scoped read tools → structured plan → domain validation
       → guardrail → pending action → explicit user confirmation → DRAFT plan
```

训练完成后可提交疲劳、疼痛与备注；Agent 基于最近 28 天的完成率、工作组 RPE、容量和 PR 趋势生成可解释调整。疼痛和数据不足会阻断草案，确认通过后只创建新的 DRAFT，不直接修改 ACTIVE 计划。

会话与消息以 PostgreSQL 为真源并默认保留 180 天，Redis 只缓存最近 30 条消息（TTL 2 小时）；缓存故障时自动回源数据库。Web 支持跨设备历史、重命名、归档、删除和待确认动作恢复。长期偏好保存在 PostgreSQL；每次执行和工具调用分别写入 `agent_execution`、`agent_tool_call`，工具入参不接受 `userId`，统一继承 JWT 当前用户。

LLM 调用链路：

```text
Prompt Registry + Model Router → Primary → Fallback → RULE_WORKFLOW
                                      ↓
                         Token / Cost / Latency Audit
```

模型只输出结构化决策和文本。服务端继续控制身份、owner、Tool 白名单、Guardrail、确认与持久化；网络/限流/网关错误有限重试，Schema、安全和权限错误不重试。

## 技术栈

- Java 21、Spring Boot 3.5、Spring MVC、Validation、Spring Security JWT
- MyBatis-Plus、PostgreSQL + pgvector、Elasticsearch、Flyway、Redis、Caffeine、Kafka
- Micrometer、OpenTelemetry、Prometheus、Grafana、Tempo、Loki
- SpringDoc OpenAPI、JUnit 5、Mockito、Testcontainers、JaCoCo、CycloneDX
- Docker Compose、Kustomize、GitHub Actions、GHCR

## 一键启动

1. 复制 `.env.example` 为 `.env`，替换数据库密码、至少 32 字节的 JWT 密钥及运维 Token。
2. 启动并验证：

```powershell
docker compose up --build -d
docker compose ps
curl.exe http://localhost:9091/actuator/health
```

启动完整可观测栈：

```powershell
$env:OTEL_TRACING_EXPORT_ENABLED = "true"
docker compose --profile observability up --build -d --wait
```

Swagger（仅开发环境）：<http://localhost:8080/swagger-ui.html>。Compose 将 Actuator 管理端口隔离到仅本机可访问的 <http://localhost:9091/actuator/health>；`prod` profile 默认关闭 Swagger/OpenAPI。

### 演示数据

演示数据仅用于本地 Docker 环境，采用显式脚本而非 Flyway 自动迁移，避免进入生产数据库。先启动 Compose 后执行：

```powershell
.\scripts\seed-demo-data.ps1
```

然后使用 `demo_athlete` / `FitPilotDemo2026!` 登录。脚本可重复执行，只维护该演示账号的数据；包含已激活的训练计划、5 次已完成训练、趋势指标、个人纪录、通知和一篇待索引的卧推知识库样例。

只在本机开发时，也可先启动 pgvector PostgreSQL、Redis、Kafka 和 Elasticsearch，再执行：

```powershell
$env:DB_PASSWORD = "your-password"
$env:JWT_SECRET = "your-at-least-32-byte-development-secret"
mvn spring-boot:run
```

项目自带 `.mvn/maven.config`，依赖缓存写入项目内，避免 Windows 全局 Maven 仓库权限问题。

## 核心 API

| 能力 | API |
|---|---|
| 注册 / 登录 | `POST /api/v1/auth/register`、`POST /api/v1/auth/login` |
| 用户 / 身体数据 | `GET /api/v1/users/me`、`PUT /profile`、`POST/GET /body-metrics` |
| 动作库 | `GET /api/v1/exercises`、`GET /api/v1/exercises/{id}` |
| 训练计划 | `POST/GET /api/v1/training-plans`、`POST /{id}/activate` |
| Workout | `POST/GET /api/v1/workouts`、动作/Set 增删改、取消、完成 |
| PR | `GET /api/v1/personal-records`、动作当前 PR / 历史 |
| Analytics | `GET /api/v1/analytics/overview`、动作进度、体重趋势 |
| PR 排行榜 | `GET /api/v1/leaderboards/exercises/{exerciseId}` |
| 缓存统计 | `GET /api/v1/performance/cache-stats` |
| PR 通知 | `GET /api/v1/notifications`、`POST /{id}/read` |
| 事件运维 | `GET /api/v1/operations/events/status`、死信查询/回放 |
| RAG 检索与反馈 | `GET /api/v1/rag/search`、`PUT /api/v1/rag/retrievals/{id}/feedback` |
| 知识库运维 | 文档摄取/版本历史/恢复/删除状态，反馈审核与汇总 |
| Agent | 会话分页/归档/删除、持久化历史、发送消息、跨设备恢复待确认动作、长期偏好 |
| Agent 评测 | `GET /api/v1/operations/agent/metrics`、标注期望工具 |
| LLM 运维 | `GET /api/v1/operations/llm/status`、`GET /invocations` |
| 离线评测 | `POST /api/v1/operations/evaluations/agent/runs`、`POST /rag/runs`、`GET /runs/{id}` |
| MCP | `POST /mcp`（JWT + MCP 2026-07-28 stateless headers） |

除注册、登录、动作库、Swagger 和健康检查外，请发送 `Authorization: Bearer <token>`。

## 关键业务保证

- 计划创建、计划激活、Workout 完成均为事务；同一用户数据库层只允许一个 ACTIVE 计划。
- Set 编号通过数据库行锁串行分配，并由唯一约束兜底。
- 完成 Workout 幂等；PR 来源与类型有唯一约束，不会重复生成。
- Workout 状态与 Outbox 事件原子提交；Kafka 不可用时核心写入保持可用。
- 所有消费者使用数据库 Inbox 幂等，失败自动重试并进入 Kafka DLT 与数据库死信表。
- 知识文档记录来源治理、不可变版本和生命周期；检索结果返回 Parent Context、`retrievalId` 与治理 Citation。
- 失效/撤销/待删除文档在 PostgreSQL 与 Elasticsearch 双侧过滤；删除先移除 Chunk，再通过持久化任务重试 ES 传播。
- RAG 用户反馈采用 Upsert，人工审核后才进入动态评测集；分类 Recall@5/MRR 回退超过 5pp 或 Citation Validity 非 100% 会阻断评测。
- pgvector 使用 384 维向量和 HNSW cosine 索引，BM25 与向量结果通过 RRF 融合并二次排序。
- Embedding 默认使用可离线测试的确定性本地实现，生产可切换到返回 384 维向量的 OpenAI-compatible 服务。
- Agent 写工具必须依次通过结构化反序列化、领域规则、Guardrail 和一次性用户确认；确认令牌只存 SHA-256，过期或重放均拒绝。
- 在线评测输出任务成功率、规则违规率和有标注样本的工具选择正确率，不用“回答像不像 AI”替代业务验收。
- LLM 主端点失败自动切备用端点，双端点失败使用规则 Workflow；任何降级均不放宽写工具安全链。
- 所有运维 API 统一使用 `OPERATIONS_TOKEN` 和 `X-Operations-Token`，采用常量时间比较且不记录 Token。
- LLM 审计脱敏并默认保留 30 天；Prompt、模型、Token、费用、时延与降级状态可追踪。
- PR 使用 Epley 公式，支持最大重量、Estimated 1RM、3/5/8/10RM、单组最大容量。
- Flyway V1-V15 管理全部表、外键、查询索引和 50 个动作种子。
- Agent 产品价值指标覆盖 D7 会话留存、建议决策漏斗、确认转化、规则降级、单次成功成本，以及训练调整前后的完成率、疼痛、容量和 PR；通过受保护 Operations API、Prometheus 与 Grafana 展示。
- 写请求可携带 `Idempotency-Key`，Redis 原子占位并回放成功响应。
- API 和登录分别使用 Redis Lua Token Bucket；Lua compare-and-delete 安全释放分布式锁。

## 测试

```powershell
./scripts/run-quality-gate.ps1
```

统一门禁依次执行 Maven、真实 ESLint、TypeScript 类型检查、Vitest + React Testing Library + MSW 覆盖率测试、生产构建和 Playwright Chromium E2E。首次运行会安装锁定依赖和 Chromium；已准备环境可使用 `-SkipInstall -SkipBrowserInstall`，但不会跳过任何测试。

`mvn verify` 额外执行 pgvector、Elasticsearch、Redis、Kafka Testcontainers E2E 和 Mock OpenAI-compatible 端到端链路。Maven Enforcer 禁止 `skipTests`、`skipITs`、`maven.test.skip`，Surefire/Failsafe 要求测试集非空，最终门禁解析两类 XML 报告并要求跳过数严格为 0；同时阻断整体行覆盖率低于 60% 或关键包低于 70% 的构建。

当前功能验收基线已执行 `mvn verify`：22 个 Surefire/Failsafe 报告、46 个测试，失败 0、错误 0、跳过 0；Testcontainers 实际启动 PostgreSQL/pgvector、Redis、Kafka 和 Elasticsearch，Flyway V1-V14 与 JaCoCo 门禁通过。Web 同一功能基线已通过 ESLint、TypeScript、14 个 Vitest/RTL 测试、生产构建和 3 个 Playwright Chromium 场景。原始结论见 [P1 AI 产品价值验收报告](docs/release/p1-ai-product-validation.md)。

前端也可单独执行：

```powershell
cd web
npm run lint          # ESLint
npm run typecheck     # TypeScript
npm run test:coverage # Vitest / RTL / MSW
npm run test:e2e      # Playwright
npm run quality       # 全部前端门禁
```

V2 的事件契约、故障语义和回放手册见 [事件驱动架构](docs/architecture/v2-events.md)。
V3 的数据模型、检索公式、配置和运维手册见 [Hybrid RAG 架构](docs/architecture/v3-rag.md)。
V4 的 Workflow、Tool 安全边界、确认协议、Memory、审计和评测见 [Agent 架构](docs/architecture/v4-agent.md)。
V5 的 LLM Gateway、评测、可观测、供应链和部署边界见 [Production Ready 架构](docs/architecture/v5-production.md)，故障与恢复流程见 [运维手册](docs/runbooks/)。
V6 的持久化会话、训练计划调节与 RAG 治理见 [AI 产品价值闭环](docs/architecture/v6-ai-product.md)。
当前能力边界、项目亮点与分阶段演进顺序见 [项目亮点与后续开发计划](docs/roadmap/FitPilot-项目亮点与后续开发计划.md)。

## 交付门禁

`CI` 执行零跳过测试、CycloneDX SBOM 依赖扫描、Gitleaks、Trivy 镜像扫描和构建验证；只有最新 `main` 提交的 CI 全部成功后，`Release` 才发布 GHCR 多架构镜像并归档不可变 digest 清单，过期 CI 结果不能覆盖发布标签；`Production Delivery Gate` 只接受 digest 与精确 Kubernetes context，依次执行隔离备份恢复、Migration、Rollout、Rollback/候选恢复和两阶段密钥轮换。

| 证据层 | revision | 状态 | 原始报告 |
|---|---|---|---|
| 当前 V6 本地功能验收 | `6a646299c81cbfc0fcd5f8f99e750226cb4642eb` | `PASS`：46 个后端测试零跳过，V1-V14 迁移、Web 14 个组件测试与 3 个浏览器场景通过 | [P1 AI 产品价值验收](docs/release/p1-ai-product-validation.md) |
| 远端 CI / Release / GHCR | `1d98621891ff92d98ad57c77ff212015b641681f` | `PASS`：CI、安全扫描、多架构镜像、Digest、SBOM、provenance 已归档 | [P1 远端发布验收](docs/release/p1-delivery-validation.md) |
| 本机 Kind 交付演练 | `1d98621891ff92d98ad57c77ff212015b641681f` | `PASS`：Migration、Rollout、Rollback、备份恢复和双密钥轮换脚本已演练 | [P1 远端发布验收](docs/release/p1-delivery-validation.md#本机-kubernetes-演练pass) |
| 真实生产集群 | 无已执行 revision | `SKIPPED`：未执行 Production Delivery Gate，不代表通过或阻塞 | [P1 远端发布验收](docs/release/p1-delivery-validation.md#production-delivery-gateskipped) |

因此，当前 `6a64629` 具备本地全量功能验收证据，但尚未重新形成对应的远端 CI/Release 镜像证据；已有远端制品只证明 `1d98621`，项目也不得表述为已在真实生产集群上线。

在没有生产凭据时，可用一次性 Kind 集群真实执行同一套脚本：

```powershell
.\scripts\delivery\run-kind-delivery-drill.ps1 -KindExecutable kind
```

运行手册见 [发布与回滚](docs/runbooks/release-rollback.md)、[备份恢复](docs/runbooks/backup-restore.md) 和 [密钥轮换](docs/runbooks/secret-rotation.md)。

## V1 性能验证

```powershell
$env:RATE_LIMIT_ENABLED = "false" # 隔离测试缓存读路径
docker compose up --build -d
.\load-test\run-v1.ps1
```

本机 10 秒分档实测的热点动作详情读取：100/500 QPS 无丢弃，1000 QPS 实际 998.83 req/s、0% HTTP 失败、P95 1.76ms，但有 6 次 dropped iterations。原始数据和边界说明见 [V1 性能报告](docs/performance/v1-benchmark.md)。该结果只代表单机热点 GET，不代表完整业务容量。

## 历史 V5 生产验证

```powershell
./scripts/run-production-validation.ps1
```

脚本执行 Maven/Testcontainers、完整 observability 在线联调与真实告警演练、30 分钟混合流量、5 分钟突发流量、运行时资源采集和隔离环境清理，并生成 [P0 生产验收报告](docs/release/p0-production-validation.md)。性能结果见 [V5 验证报告](docs/performance/v5-production-validation.md)，远端门禁见 [V5 发布清单](docs/release/v5-validation.md)。该报告记录的是 V5/Flyway V9 历史性能基线，不替代 V6/V14 功能验收，也不能外推为真实生产容量。

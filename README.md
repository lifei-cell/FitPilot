# FitPilot V6.0

FitPilot 是一个面向力量训练的 Web 应用。用户可以按计划训练、在弱网下记录训练组并在联网后补传，也可以回看历史、趋势和个人纪录。AI 教练根据训练记录和知识库回答问题、提出计划调整，用户确认后保存为新草案。

> 2026-11-26，试用版候选已合入 `main`（`e9371b3`），合并提交的 CI 已通过。Android 和 iOS 真机验收待执行。 [PR #1](https://github.com/lifei-cell/FitPilot/pull/1) · [主分支 CI](https://github.com/lifei-cell/FitPilot/actions/runs/45751732022)

## 主要功能

- **计划与开练**支持套用预设或手动创建计划。激活后可按训练日开练，系统会保存本次训练的动作和目标快照。
- **训练记录**支持图文与视频教学、沿用上次组记录快速录入、组间计时和进度查看。训练结束后可回看历史、负重趋势和个人纪录。
- **弱网恢复**会在浏览器保存训练草稿和待同步组，联网后用同一操作 ID 补传。服务端用数据库回执去重，跨设备冲突时保留本地记录并暂停补传。 [恢复机制](docs/release/workout-auto-recovery.md)
- **AI 教练**支持持久化会话、带引用的知识问答和基于训练反馈的计划调整。 [AI 产品能力](docs/architecture/v6-ai-product.md)

## 架构

目录入口：[项目结构与维护规则](docs/project-structure.md) · [文档导航](docs/README.md) · [脚本导航](scripts/README.md) · [Web 开发](web/README.md)。

训练并发控制：[统一写入锁协议](docs/release/workout-write-concurrency.md)（2026-09-17，含事务、行锁、唯一约束的职责与并发验证）。

选型对照：[Kafka、缓存与检索的简单方案实验](docs/performance/architecture-comparison-2026-09-17.md)（2026-09-17，包含局部收益、负例、原始数据及面试回答）。

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
- 用户资料、ACTIVE 计划在数据库事务提交后删除本实例 L1 与共享 L2；跨实例展示允许短暂旧值，默认 L1 写入后 5 秒过期，L2 为 30 秒加最多 5 秒抖动。关键业务判断读数据库，详见[短 TTL 缓存策略](docs/release/short-ttl-cache-consistency.md)。
- Redis 故障时缓存回源数据库、限流降级为进程内 Token Bucket，核心业务保持可用。

训练完成事件链路：

```text
Workout + Outbox（同一事务）→ Kafka → PR / Analytics → PR Event → Notification
```

Kafka 故障不会回滚已完成的 Workout。PR、分析投影和通知采用最终一致性；恢复后 Outbox 自动补发。

Outbox 超时重领会更换领取 token；发送前续租及发送结果回写均校验领取权，防止旧执行者晚返回覆盖新状态。详见 [V20 领取保护与故障测试](docs/release/outbox-claim-ownership.md)。

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

模型只输出结构化决策和文本。服务端继续控制身份、owner、Tool 白名单、Guardrail、确认与持久化；网络/限流/网关错误有限重试，Schema、安全和权限错误不重试。主备与重试共享 20 秒 Deadline，Semaphore Bulkhead 限制并发，熔断恢复只放行单个半开探测。

## 技术栈

- Java 21、Spring Boot 4.0.10、Spring MVC、Validation、Spring Security JWT
- React 19、TypeScript、Vite、TanStack Query
- MyBatis-Plus、PostgreSQL + pgvector、Elasticsearch、Flyway、Redis、Caffeine、Kafka
- Micrometer、OpenTelemetry、Prometheus、Grafana、Tempo、Loki
- SpringDoc OpenAPI、JUnit 5、Mockito、Testcontainers、JaCoCo、CycloneDX
- Docker Compose、Kustomize、GitHub Actions、GHCR

## 一键启动

在仓库根目录运行。需要可用的 Docker Engine 和 Docker Compose v2。首次启动时复制环境模板，并在 `.env` 中设置 `DB_PASSWORD`、`JWT_SECRET`（至少 32 字节）和 `OPERATIONS_TOKEN`。

```powershell
Copy-Item .env.example .env
```

保存配置后，启动并验证服务。

```powershell
docker compose up --build --wait
curl.exe -fsS http://localhost:4173/healthz
curl.exe -fsS http://localhost:9091/actuator/health/readiness
```

打开 [训练 Web](http://localhost:4173) 注册并登录。开发环境提供 [Swagger](http://localhost:8080/swagger-ui.html)，Actuator 的 `9091` 端口仅绑定本机。`prod` profile 默认关闭 Swagger/OpenAPI。

启动完整可观测栈：

```powershell
$env:OTEL_TRACING_EXPORT_ENABLED = "true"
docker compose --profile observability up --build -d --wait
```

默认使用规则工作流，训练流程可以直接体验。需要本地模型推理时，可以通过 Pi 接入已授权的 ChatGPT 订阅。 [Pi 接入说明](integrations/pi-coach/README.md)

### 演示数据

演示数据仅用于本地 Docker 环境，采用显式脚本而非 Flyway 自动迁移，避免进入生产数据库。先启动 Compose 后执行：

```powershell
.\scripts\development\seed-demo-data.ps1
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
- Flyway 迁移覆盖 V1–V23。V19 增加训练组离线回执，V20 增加 Outbox 领取 token，V21 为知识索引增加版本写入保护，V22 隔离在线知识与评测语料，V23 为训练完成事件关联个人纪录消费回执增加索引。
- Agent 产品价值指标覆盖 D7 会话留存、建议决策漏斗、确认转化、规则降级、单次成功成本，以及训练调整前后的完成率、疼痛、容量和 PR；通过受保护 Operations API、Prometheus 与 Grafana 展示。
- 写请求可携带 `Idempotency-Key`，Redis 原子占位并回放成功响应。
- API 和登录分别使用 Redis Lua Token Bucket；Lua compare-and-delete 安全释放分布式锁。

## 测试

在仓库根目录执行统一质量门禁。需要 PowerShell、Java 21、Maven、Node.js 22.13 或更高版本以及可供 Testcontainers 使用的 Docker Engine。

```powershell
./scripts/run-quality-gate.ps1
```

统一门禁依次执行 Maven、真实 ESLint、TypeScript 类型检查、Vitest + React Testing Library + MSW 覆盖率测试、生产构建和 Playwright Chromium E2E。首次运行会安装锁定依赖和 Chromium；已准备环境可使用 `-SkipInstall -SkipBrowserInstall`，但不会跳过任何测试。

`mvn verify` 额外执行 pgvector、Elasticsearch、Redis、Kafka Testcontainers E2E 和 Mock OpenAI-compatible 端到端链路。ArchUnit 自动阻断 Controller 直连 Mapper、领域层反向依赖基础设施、跨模块绕过 Application Service/领域事件以及业务模块循环依赖。Maven Enforcer 禁止 `skipTests`、`skipITs`、`maven.test.skip`，Surefire/Failsafe 要求测试集非空，最终门禁解析两类 XML 报告并要求跳过数严格为 0；同时阻断整体行覆盖率低于 60% 或关键包低于 70% 的构建。

主分支合并提交 `e9371b3` 的 CI 通过了 80 个后端单元测试和 63 个集成测试，失败、错误、跳过均为 0。Web 通过了 51 个 Vitest 测试、9 个常规 Playwright 场景和 3 个离线恢复场景。3 个 Pi 适配器测试和 V22 发布门禁回归也已通过。 [CI 日志与产物](https://github.com/lifei-cell/FitPilot/actions/runs/45751732022)

前端也可单独执行：

```powershell
cd web
npm ci
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
面试复习可从 [中间件与原理面试手册](docs/interview/FitPilot-中间件与原理面试手册.md) 开始，按源码落点学习 Java/Spring、PostgreSQL、Redis、Kafka、RAG、Agent、可观测与交付，并使用追问速查进行压力复盘。

## 交付门禁

`CI` 检查测试、构建、部署清单，并执行 Gitleaks、基于 CycloneDX SBOM 的依赖扫描及 Trivy 镜像扫描。`Release Image` 仅发布已通过 CI 且仍为最新 `main` 的 revision，成功时归档后端与 Web 的 GHCR 镜像 digest 和构建证明。

| 验收 | Revision | 状态与证据 |
|---|---|---|
| 试用版主分支 CI | `e9371b3` | 测试、构建与安全扫描通过。[主分支 CI](https://github.com/lifei-cell/FitPilot/actions/runs/45751732022) |
| GHCR 镜像发布 | `e9371b3` | 发布进度与成功后的 digest 清单见 [Release Image](https://github.com/lifei-cell/FitPilot/actions/runs/45752749934)。 |
| Android / iOS 真机 | 试用版 | 移动 Chromium 已覆盖弱网、离线恢复和跨设备冲突。物理设备上的锁屏、进程终止与系统存储回收待验收。[真机执行卡](docs/release/workout-recovery-device-validation-2026-09-19.md#真机执行卡) |
| 真实生产集群 | — | 待执行生产交付门禁。[首次外网部署](docs/runbooks/first-public-deploy.md) |

`Production Delivery Gate` 手动触发，要求不可变镜像 digest 和精确 Kubernetes context。首次跨越 V22 或接管兼容性未验证的旧后端时，先停止旧后端和评测 Worker，再执行迁移与上线。回滚演练只适用于已验证兼容的旧镜像，备份恢复与密钥轮换通过工作流选项执行。 [发布与回滚](docs/runbooks/release-rollback.md)

历史数据见 [V20 本地验收](docs/release/v20-functional-validation.md) 和 [历史 CI、GHCR、Kind 演练及 Git Note 查询](docs/release/p1-delivery-validation.md)。

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
./scripts/production-validation/run-production-validation.ps1
```

脚本执行 Maven/Testcontainers、完整 observability 在线联调与真实告警演练、30 分钟混合流量、5 分钟突发流量、运行时资源采集和隔离环境清理，并生成 [P0 生产验收报告](docs/release/p0-production-validation.md)。性能结果见 [V5 验证报告](docs/performance/v5-production-validation.md)，远端门禁见 [V5 发布清单](docs/release/v5-validation.md)。该报告记录的是 V5/Flyway V9 历史性能基线，不替代 V6/V14 功能验收，也不能外推为真实生产容量。

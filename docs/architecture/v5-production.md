# FitPilot V5 Production Ready 架构

## 1. 边界与原则

V5 保持模块化单体。LLM 只提供结构化规划与文本生成，JWT 身份、owner 查询、Tool 白名单、领域规则、Guardrail、一次性确认和持久化始终由后端掌控。系统不实现 Multi-Agent，也不把 Agent 拆成独立服务。

## 2. LLM Gateway

```text
Agent Workflow
  → Prompt Registry + Model Router
  → Primary OpenAI-compatible endpoint
  → Fallback OpenAI-compatible endpoint
  → RULE_WORKFLOW
```

- 小模型处理意图、Query Rewrite、Memory Extraction；中模型处理训练分析；强模型生成训练计划。
- 连接超时 3 秒、请求超时 15 秒。网络错误、429、502/503/504 最多重试两次并抖动退避；Schema、安全和权限错误不重试。
- 规划只接受 `AgentDecision(intent, toolCalls, responseMode)`。Tool 必须在服务端白名单，参数出现 `userId` 立即拒绝并降级。
- 训练计划反序列化为 `TrainingPlanDtos.CreateRequest`，通过现有校验链后生成 pending action；确认令牌只存摘要、绑定用户、单次消费。
- RAG 上下文以不可信数据包裹，不得触发 Tool 或覆盖系统指令；引用由真实 `RetrievedContext` 生成。

`llm_invocation` 逐次记录端点、模型、Prompt 版本、Token、费用、时延和错误码；`agent_execution` 汇总最终模型、总 Token/费用和降级状态。审计输入经过敏感信息脱敏，明细保留 30 天。

## 3. 评测

版本化数据集位于 `src/main/resources/eval/`：150 条中文 Agent 用例和 50 条 RAG 真值。运维 API 异步创建评测任务，结果写入 `agent_eval_run/result` 与 `rag_eval_run/result`。

门禁：Tool Selection ≥95%、Task Success ≥95%、违规率为 0；RAG Recall@5 ≥85%、MRR ≥0.75，并校验引用属于本次检索上下文。CI 使用 Mock OpenAI-compatible Server；真实模型评测保留为夜间/手动任务。

## 4. 可观测

```text
HTTP → Agent → LLM → Tool → RAG → PostgreSQL / Redis / Kafka
  └─ RequestId + TraceId → OTel Collector → Tempo
JSON Log → Promtail → Loki
Micrometer → Prometheus → Grafana / Alert Rules
```

Span 只记录匿名用户标识和低基数字段，不记录问题正文、Tool 响应或凭据。预置 System、API、Agent、Kafka、RAG、LLM Cost 六个 Dashboard，以及 API 错误/P95、Agent 成功、规则违规、LLM fallback、outbox 年龄和 DB Pool 告警。

默认 Compose 基线关闭 OTLP exporter；启用 `observability` profile 时设置 `OTEL_TRACING_EXPORT_ENABLED=true`。这样未启动 Collector 的基础环境不会产生持续导出错误。

## 5. 交付与部署

PR 流程执行 Java 21 `mvn verify`、0 跳过校验、JaCoCo、Gitleaks、CycloneDX SBOM 依赖扫描和 Trivy 镜像扫描。Release Workflow 仅在最新 `main` 的 CI 成功后构建非 root、多架构 GHCR 镜像，附带版本、Git SHA、SBOM 和 provenance。

Compose 镜像以 UID 10001 运行并兼容只读根文件系统，设置资源限制、日志轮转、健康检查和 30 秒优雅停机。Secret 仅从环境变量注入。

Kustomize production overlay 使用外部 PostgreSQL、Redis、Kafka 和 Elasticsearch：

- 应用起始 2 副本，滚动升级 `maxUnavailable=0`，HPA 2–10、CPU 70%。
- 配置 ServiceAccount、Ingress、PDB、NetworkPolicy、只读文件系统和能力丢弃。
- Flyway 由独立 migration overlay/Job 执行，应用 Pod 设置 `FLYWAY_ENABLED=false`。
- 迁移只允许向前兼容；发布失败停止 rollout 并回滚应用镜像，不执行 down migration。

## 6. 验证入口

```powershell
mvn verify
docker compose --env-file .env.example up -d --build --wait
./load-test/run-v5.ps1 -Scenario all
./scripts/invoke-failure-drills.ps1 -OperationsToken $env:OPERATIONS_TOKEN -IncludeLlm
kubectl kustomize deploy/k8s/overlays/production
kubectl kustomize deploy/k8s/overlays/production-migration
```

详细恢复步骤见 `docs/runbooks/`。生产发布证据必须区分已在本机执行的验证和只能在真实 GHCR/Kubernetes/GitHub Environment 中完成的门禁。

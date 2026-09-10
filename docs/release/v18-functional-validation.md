# V18 当前功能验收

验证日期：2026-09-10。功能 revision：`b3d708ac4249331bf74e1541481caca115dc55ff`。本报告证明该 revision 的本地统一质量门禁；远端 CI、Release、GHCR、SBOM 与 provenance 必须在同一精确 SHA 完成后单独记录，真实生产状态另行说明。

## 统一质量门禁：PASS

执行命令：

```powershell
$env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine'
./scripts/run-quality-gate.ps1 -SkipInstall -SkipBrowserInstall
```

- 最终输出 `QUALITY_GATE=PASS`。
- 后端共 35 个 Surefire/Failsafe XML 报告、79 个测试，失败 0、错误 0、跳过 0；其中 61 个单元测试和 18 个 Testcontainers 集成测试全部实际执行。
- Testcontainers 连接 Docker Desktop Engine 29.7.2，实际启动 PostgreSQL/pgvector、Redis、Kafka、Elasticsearch 与 Mock OpenAI-compatible 测试依赖。
- Flyway V1-V18 全量迁移成功；V18 增加 RAG 反馈离线实验配置、结果唯一约束和结构化实验报告持久化。
- ArchUnit 模块边界门禁通过，覆盖率门禁通过；JaCoCo 后端行覆盖率为 84.40%（3831/4539）。
- Web 通过 ESLint、TypeScript、18 个 Vitest 文件中的 36 个测试、生产构建和 7 个 Playwright Chromium 场景；语句 69.61%、分支 66.17%、函数 60.06%、行覆盖率 71.69%。

## 本轮能力增量

- 仅使用 `NOT_HELPFUL + APPROVED` 反馈构造冻结评测集，按 run、profile、category 和 external id 隔离实验数据，不修改线上排序权重或污染线上索引。
- 提供 7 组可复现的 RAG 对照 profile：`baseline`、两组 chunking、两组 RRF 权重和两组 rerank 策略。
- 新增 `POST /api/v1/operations/evaluations/rag/feedback-experiments`，输出总体及分类 Recall@5、MRR、Citation Validity、baseline 差异和加权得分。
- 只有 Citation Validity 达到 100% 时才允许推荐 profile；空检索上下文计为无效 citation。推荐得分为 `0.5*Recall@5 + 0.3*MRR + 0.2*CitationValidity`。
- RAG 评测任务继续使用持久化生命周期和临时索引隔离，完成后清理实验文档，避免离线实验影响后续线上检索。

## 证据边界

- 该结果是 Windows + Docker Desktop 本地验收，不是生产容量或真实 Kubernetes 运行证据。
- CI 使用 Mock OpenAI-compatible 服务，证明协议、解析、审计、降级和 Workflow 集成，不证明真实模型质量或真实 Provider 评测结果。
- revision `b3d708a` 的精确 SHA CI Run `34463093054` 已完成，但 `verify` 在 Trivy 依赖扫描阶段因 CycloneDX SBOM 检出 `CRITICAL CVE-2026-75595`（`io.netty:netty-handler:4.1.136.Final`）失败；61 个单元测试、18 个集成测试、Web 质量门禁和 secret-scan 均通过，因此未触发该 revision 的 Release、GHCR 和 provenance 闭环。
- 本报告之后已将 Netty 升级到 `4.1.137.Final`；修复发布 revision `c55e26d52fa74010f9d9467911aa2de3e7de3b9d` 的精确 SHA CI、Release、GHCR、SBOM 和 provenance 均已完成，完整 Run、Digest、Artifact 和验证命令见 [P1 远端发布与生产交付验收](p1-delivery-validation.md)。
- 该远端闭环只证明 `c55e26d` 的 CI 与 GHCR 发布，不证明真实模型质量、生产容量或生产集群上线；Production Delivery Gate 仍为 `SKIPPED`。
- Production Delivery Gate 仍为 `SKIPPED`，不得表述为已生产上线。

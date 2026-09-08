# V17 当前功能验收

验证日期：2026-09-08。功能 revision：`c1c9edec2472ea4e3cb1655b1eae13e6d79ac16c`。本报告证明该 revision 的本地统一质量门禁；远端 CI、Release、GHCR、SBOM 与 provenance 按最终 `main` revision 记录在 `refs/notes/release-evidence`，真实生产状态另见 [远端发布与生产交付验收](p1-delivery-validation.md)。

## 统一质量门禁：PASS

执行命令：

```powershell
./scripts/run-quality-gate.ps1 -SkipInstall -SkipBrowserInstall
```

- 最终输出 `QUALITY_GATE=PASS`。
- 后端共 30 个 Surefire/Failsafe 报告、65 个测试，失败 0、错误 0、跳过 0；其中 47 个单元/组件测试和 18 个 Testcontainers 集成测试全部实际执行。
- Testcontainers 连接 Docker Desktop 4.88.1 / Engine 29.7.2，实际启动 PostgreSQL/pgvector、Redis、Kafka、Elasticsearch 与 Mock OpenAI-compatible 测试依赖。
- Flyway V1-V17 全量迁移成功；V16 增加 Workout 业务幂等约束，V17 增加评测队列、Deadline、Lease、Heartbeat 与恢复生命周期。
- JaCoCo 门禁通过，后端行覆盖率 83.12%（3299/3969）。
- Web 通过 ESLint、TypeScript、18 个 Vitest 文件中的 36 个测试、生产构建和 7 个 Playwright Chromium 场景；语句 69.61%、分支 66.17%、函数 60.06%、行覆盖率 71.69%。

## 本轮能力增量

- 通用写请求幂等增加规范化请求指纹、执行中冲突检测、响应大小上限和 Workout 数据库业务幂等，避免 Redis 记录丢失后重复创建训练。
- Operations API 统一进入 Spring Security FilterChain，以常量时间比较校验 Token。
- LLM 主备、重试和退避共享总 Deadline，并增加 Semaphore Bulkhead、按端点熔断、`Retry-After` 上限和线程取消传播。
- Redis 故障时 readiness 保持可用，缓存、限流与分布式锁按明确策略降级。
- 异步评测增加有界线程池、持久化队列、租约续期、过期任务恢复、Deadline 和拒绝状态，避免任务因进程重启永久停留在 `RUNNING`。

## 依赖安全补强

- 首次远端 CI `34230400711` 的统一质量门禁和 Gitleaks 已通过，但 Trivy 0.70.0 阻断了 Spring Boot 3.5.16 默认管理的 `tomcat-embed-core:10.1.55`，对应 `CVE-2026-65182`、`CVE-2026-65905`、`CVE-2026-68525`。
- 使用 Spring Boot 依赖管理属性将嵌入式 Tomcat 整组升级到 Maven Central 已发布的 `10.1.59`，高于 Trivy 给出的 `10.1.58` 修复线；不通过 `.trivyignore` 绕过 CRITICAL 漏洞。
- 升级后重新执行统一质量门禁和同版本 Trivy SBOM 扫描：后端 65 项、前端 36 项、E2E 7 项均通过且零跳过，HIGH/CRITICAL 结果为 0。
- 首次安全升级 Release `34232980670` 已通过候选验收及后端、Web 镜像 Trivy，但多架构构建在 arm64 QEMU 中重复执行单测时触发墙钟计时抖动；Docker 构建改为仅打包，测试继续由 Release 的 `Verify release candidate` 前置门禁负责，避免同一流水线重复执行且不降低质量门。

## 证据边界

- 该结果是 Windows + Docker Desktop 本地验收，不是生产容量或真实 Kubernetes 运行证据。
- CI 使用 Mock OpenAI-compatible 服务，证明协议、解析、审计、降级和 Workflow 集成，不证明真实模型质量。
- 远端发布证据必须与最终 `main` revision、CI Run、Release Run、镜像 Digest、SBOM 和 provenance 一一对应；最终不可变记录写入 Git Note，避免为回填 Run ID 再改变 `main` revision。
- Production Delivery Gate 仍为 `SKIPPED`，不得表述为已生产上线。

# V20 当前功能验收

日期：2026-09-22。被测实现 revision：`9bd259c7f4469826d094da1add6ef4bcad604a25`。本报告只记录本地统一质量门禁；精确远端 CI、Release、GHCR、SBOM 与 provenance 证据写入发布提交的 `refs/notes/release-evidence`，真实 Production Delivery Gate 仍单独判定。

## 执行入口

Windows + Docker Desktop 环境设置 `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` 后执行：

```powershell
.\scripts\run-quality-gate.ps1 -SkipInstall -SkipBrowserInstall
```

两个参数只跳过已经完成的依赖和 Chromium 安装，不跳过测试。首次受限沙箱运行无法访问 Docker 命名管道，集成测试被跳过并被覆盖率门禁阻断，不计作通过证据；允许访问 Docker 后重新从 `clean` 开始执行并得到 `QUALITY_GATE=PASS`。

## 后端结果

- Surefire：74 个单元测试；Failsafe：48 个集成测试；合计 122，失败 0、错误 0、跳过 0。
- 43 个 Surefire/Failsafe XML 报告通过零跳过门禁。
- Testcontainers 实际启动 PostgreSQL/pgvector、Redis、Kafka 和 Elasticsearch；Flyway 20 个迁移全部应用至 V20。
- JaCoCo 行覆盖率 85.75%（4013/4680），整体 60% 与关键包 70% 门禁均通过。
- CycloneDX `target/bom.json` 已生成，SHA-256 为 `AA8F3596107033DCF773109565B82C514138F69D473985AED6E68163B287374F`。

## Web 结果

- ESLint、TypeScript 类型检查和生产构建通过。
- Vitest / React Testing Library / MSW：20 个测试文件、44 个测试全部通过；行覆盖率 76.26%（617/809）。
- Playwright：8 个常规 Chromium 场景通过；3 个离线/弱网/跨设备恢复场景通过。

## 证据边界

- 本地门禁绑定上述实现 revision；后续仅文档变化不改变被测应用行为，但远端发布必须绑定实际发布提交。
- 本地生成的 SBOM 证明构建已产出依赖清单；远端 Trivy、镜像多架构 digest、OCI SBOM 与 SLSA provenance 只能由对应 GitHub Actions 运行和发布证据证明。
- Kind 历史演练和真实生产集群不是本次本地验收内容；Production Delivery Gate 未执行前保持 `SKIPPED`。

## 远端门禁跟进

首次推送触发的 CI Run `35681334604` 发现 Gitleaks 历史扫描误报：Web 测试中的固定伪 JWT，以及性能证据 manifest 中记录的 SHA-256 被 `generic-api-key` 规则识别为密钥。仓库增加 `.gitleaks.toml`，只允许测试目录中的该固定伪 JWT，以及 `docs/performance/evidence/**/manifest.json` 中格式严格为 64 位十六进制的摘要；默认规则仍完整启用，不按整个文件或目录跳过扫描。修复后使用与 CI 相同的 Gitleaks v8.28.0 扫描完整 Git 历史，必须达到零 finding 后才能重新推送。

第二次 CI Run `35681988053` 的 `secret-scan` 已通过，但原合并步骤 `Backend and web quality gate` 失败；公开 API 仅返回退出码 1，未提供可下载的私有步骤日志，因此不能据此宣称已定位具体根因。对应 Release Run `35682575910` 因 CI 未通过而跳过。

为排除 Windows 本地偶然成功和运行环境差异，在官方 Linux 容器中复现同一门禁：

- `maven:3.9.11-eclipse-temurin-21`：后端 122 个测试全部通过、零跳过，Flyway 到 V20，覆盖率与 SBOM 门禁通过。
- `mcr.microsoft.com/playwright:v1.62.1-noble`（Node.js 24.18.1）：ESLint、类型检查、20 文件/44 个测试、生产构建及 Playwright 8+3 场景全部通过。

因此第二次远端失败目前只能标记为“未确认的远端失败”，不能归因于源码或声称已经修复。CI 将后端与 Web 拆成两个独立步骤，本地默认入口仍执行完整组合门禁；后续失败可直接归属到具体子门禁。

拆分后的 CI Run `35703778986` 证明后端门禁和 Gitleaks 均通过，并将失败精确定位到 Web 离线 E2E：跨设备场景在慢速 runner 上先发生设备 A 后台刷新，输入框被移除后 `fill()` 等待至 60 秒超时。测试改为显式控制“设备 A 尚未观察到关闭”的状态，仍验证提交集合时收到 409、保留本机记录，随后刷新观察服务器已结束。目标场景在 Windows 与官方 Linux Playwright 镜像中各连续运行 10 次全部通过，Linux 完整 Web 门禁也通过。

读取远端精确证据：

```powershell
git fetch origin refs/notes/release-evidence:refs/notes/release-evidence
git notes --ref=release-evidence show origin/main
```

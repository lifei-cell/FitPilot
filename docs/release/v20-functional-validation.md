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

读取远端精确证据：

```powershell
git fetch origin refs/notes/release-evidence:refs/notes/release-evidence
git notes --ref=release-evidence show origin/main
```

# P1 远端发布与生产交付验收

验证日期：2026-08-31。远端发布验收对象为提交 `1d98621891ff92d98ad57c77ff212015b641681f`；结论严格区分 CI、GHCR 发布、本机 Kind 演练和真实生产集群执行。

## CI 与安全门禁：PASS

- CI Run：[33352127749](https://github.com/lifei-cell/FitPilot/actions/runs/33352127749)。`verify` 与 `secret-scan` 均成功。
- 统一质量门禁执行 Maven 单元测试 22 个、集成测试 12 个，合计 34 个且失败、错误、跳过数均为 0；JaCoCo、ESLint、TypeScript、Vitest/RTL/MSW 5 个测试、生产构建和 Playwright Chromium 1 个 E2E 全部通过。
- Gitleaks、CycloneDX SBOM、依赖 Trivy、应用镜像 Trivy、Web 镜像 Trivy、Compose/Kubernetes 清单和 Prometheus/Alertmanager 配置校验全部通过。
- 三类 Trivy SARIF 使用独立 CodeQL category，只以 `HIGH,CRITICAL` 阻断发布；报告不存在时不会产生次生上传错误。
- Web 运行镜像已从存在 33 个 HIGH/CRITICAL 系统包漏洞的旧 Alpine 镜像，升级并锁定到 `nginxinc/nginx-unprivileged:1.31.4-alpine3.24-slim@sha256:d668aa123a6ec3216ba5ae6b398ae8001d5e81d3142d3659e20354fd0c3c3125`；最终 Web Trivy 门禁通过。

## Release 与 GHCR：PASS

- Release Run：[33352537058](https://github.com/lifei-cell/FitPilot/actions/runs/33352537058)。发布前重新执行完整质量门禁和两类候选镜像扫描，随后完成多架构推送、SBOM 和 provenance。
- Release Artifact：`ghcr-digests-33352537058`，Artifact ID `9744331956`；`release-manifest.json` 的归档 SHA256 为 `247655fdcfa712818b50fe2498595226e77dbad873d6898f475df0471c1f7ff1`，与校验文件一致。
- 应用镜像：`ghcr.io/lifei-cell/fitpilot@sha256:42328ef31b4966a97983de76166fbbc7aea5fd43fe86b34f8a1b655e41e0c941`。
- Web 镜像：`ghcr.io/lifei-cell/fitpilot-web@sha256:59efba331f5ab503760225e650a299aafa446913fbcafd09eb4b20eba7fcdcc4`。
- 两个 GHCR OCI Index 的 Registry Digest 均与 Release Manifest 一致，且都包含 `linux/amd64`、`linux/arm64` 和 2 个 attestation 条目；revision 与镜像 Digest 可双向追溯。

## 本机 Kubernetes 演练：PASS

- 一次性 Kind 集群已执行基线 Migration/Rollout、独立恢复库备份恢复、候选 Migration/Rollout、两个 Deployment 的 Rollback 与镜像核对、候选恢复、JWT 双密钥轮换、旧 JWT 撤销、新 Operations Token 接受与旧 Token 拒绝；最终输出 `KIND_DELIVERY_DRILL=PASS`。
- 隔离演练结束后已删除固定 Kind 集群、专用 PostgreSQL/Redis 容器和网络；数据库 URL 与轮换密钥未写入证据。
- Kind 演练只证明脚本能在真实 Kubernetes API 上运行，不等同生产集群发布。

## Production Delivery Gate：SKIPPED

- GitHub `production` Environment 已创建，仅允许 `main` 分支，并要求仓库所有者 `lifei-cell` 审批。
- Environment 当前未配置 `KUBE_CONFIG`、源/恢复数据库 URL 和轮换凭据；本机也没有生产 kube context。
- 用户于 2026-08-31 明确决定跳过 Production Delivery Gate，因此没有调度生产流水线，也没有在真实生产集群执行 Migration、2 副本 Rollout、readiness、PDB/HPA/NetworkPolicy、Rollback、备份恢复、JWT/Operations Token 轮换或旧密钥撤销。
- `SKIPPED` 不是 `PASS` 或 `BLOCKED`，不得表述为“已生产上线”“生产发布验证通过”或“旧生产密钥已撤销”。

## 最终结论

远端 CI、安全扫描、GHCR 多架构发布、Digest/Artifact/provenance 证据闭环结论为 `PASS`；本机一次性 Kubernetes 交付演练为 `PASS`；真实 Production Delivery Gate 按用户决策为 `SKIPPED`。项目已具备可追溯发布产物，但尚无真实生产集群上线证据。

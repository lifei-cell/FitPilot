# V6 远端发布与生产交付验收

验证日期：2026-09-02。最新远端发布对象为提交 `8a8e6eacf47508de4ea7caabded0179fff97ca9f`；该提交包含并后继于 V6/Flyway V15 功能验收提交 `06bc3484ba3d9de61c044dc79b0e8ab80d078963`。结论严格区分当前 CI/GHCR 发布、历史 Kind 演练和真实生产集群执行。

## CI 与安全门禁：PASS

- CI Run：[33613240218](https://github.com/lifei-cell/FitPilot/actions/runs/33613240218)。`verify` 与 `secret-scan` 均成功，执行 revision 与发布 revision 一致。
- `verify` 完成后端与 Web 统一质量门禁、CycloneDX SBOM 依赖扫描、三类 Trivy 扫描、Compose/Kubernetes 清单校验、Prometheus/Alertmanager 校验以及应用和 Web 镜像构建。
- 证据制品 `verification-evidence` 的 Artifact ID 为 `9840130421`，Gitleaks SARIF 的 Artifact ID 为 `9839842984`，均未过期。
- 首次 CI Run `33612124842` 仅因测试使用的确定性伪 JWT 被 Gitleaks 判为 `generic-api-key` 而失败。提交 `8a8e6ea` 只按 commit/path/rule/line 指纹豁免 8 个已审计测试样本；随后用 Gitleaks v8.28.0 扫描全部 43 个提交，结果为 `no leaks found`，远端重跑通过，没有扩大目录或规则级豁免。

## Release、GHCR 与 SBOM：PASS

- Release Run：[33613998840](https://github.com/lifei-cell/FitPilot/actions/runs/33613998840)。`select-revision` 已确认触发 CI 的 head SHA 仍是最新 `main`，发布前重新执行完整质量门禁和两类候选镜像扫描。
- Release Artifact：`ghcr-digests-33613998840`，Artifact ID `9840602515`；`release-manifest.json` 的 SHA256 为 `e698d35c7dc14936041e6d1a969ab6d4d8c1e37b7e2c9f0d19b4366458272ad2`，与归档校验文件一致。
- 应用镜像：`ghcr.io/lifei-cell/fitpilot@sha256:cdd65de951382b670f08114074ea31dd990e5841c61f771e3eeb6111901255d6`。
- Web 镜像：`ghcr.io/lifei-cell/fitpilot-web@sha256:794922d38d2705adbad5712cb40208421409509ee026f3fa3eb4a10fd4f08f40`。
- 两个 GHCR OCI Index 的 Registry Digest 均与 Release Manifest 一致，且都包含 `linux/amd64`、`linux/arm64` 和对应 attestation manifest。Buildx 独立读取到应用 SBOM 298,244 字节、Web SBOM 36,620 字节。

## Provenance：PASS

- Release 中两次 `actions/attest-build-provenance@v2` 均成功，predicate 为 `https://slsa.dev/provenance/v1`；证明由 Public Good Sigstore 签名，写入 Rekor，并上传到 GitHub 仓库和 GHCR。
- 应用证明：[Attestation 44655735](https://github.com/lifei-cell/FitPilot/attestations/44655735)；Web 证明：[Attestation 44655746](https://github.com/lifei-cell/FitPilot/attestations/44655746)。
- 使用已校验发布包 SHA256 的 GitHub CLI v2.99.0，从 OCI Registry 读取 bundle 并执行 `gh attestation verify --bundle-from-oci`。两个镜像均通过密码学验证，同时约束 `source-digest=8a8e6eacf47508de4ea7caabded0179fff97ca9f`、`source-ref=refs/heads/main` 和 signer workflow `lifei-cell/FitPilot/.github/workflows/release.yml`。

## 本机 Kubernetes 演练：历史 PASS

- revision `1d98621891ff92d98ad57c77ff212015b641681f` 的一次性 Kind 集群已执行 Migration/Rollout、独立恢复库备份恢复、两个 Deployment 的 Rollback 与镜像核对、JWT 双密钥轮换和 Operations Token 轮换，最终输出 `KIND_DELIVERY_DRILL=PASS`。
- 隔离演练结束后已删除固定 Kind 集群、专用 PostgreSQL/Redis 容器和网络；数据库 URL 与轮换密钥未写入证据。
- 该记录只证明旧 revision 的脚本能在真实 Kubernetes API 上运行，不是 revision `8a8e6ea` 的集群验收，也不等同生产发布。

## Production Delivery Gate：SKIPPED

- GitHub `production` Environment 已创建，仅允许 `main` 分支，并要求仓库所有者 `lifei-cell` 审批。
- Environment 未配置 `KUBE_CONFIG`、源/恢复数据库 URL 和轮换凭据；本机也没有生产 kube context。
- 用户于 2026-08-31 明确决定跳过 Production Delivery Gate，因此没有在真实生产集群执行 Migration、2 副本 Rollout、readiness、PDB/HPA/NetworkPolicy、Rollback、备份恢复或密钥轮换。
- `SKIPPED` 不是 `PASS` 或 `BLOCKED`，不得表述为“已生产上线”或“生产发布验证通过”。

## 最终结论

revision `8a8e6ea` 的远端 CI、安全扫描、GHCR 多架构发布、不可变 Digest、SBOM 和 provenance 已形成一对一可追溯闭环，覆盖 revision `06bc348` 的 V6/Flyway V15 功能。历史 Kind 演练仍绑定 `1d98621`，真实 Production Delivery Gate 仍为 `SKIPPED`。

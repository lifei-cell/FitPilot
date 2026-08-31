# 发布与回滚

## 发布

1. PR 必须通过 Java 21 编译、单元/E2E、覆盖率、Dependency Check、Gitleaks、Trivy 和 SBOM。
2. Release Workflow 推送多架构镜像及 provenance，并归档 `release-manifest.json`；部署只接受 `image@sha256:...`。
3. 先执行 `production-migration` Job。迁移必须向前兼容，应用 Pod 禁止并发 Flyway。
4. 再应用 production overlay，确认 2 个起始副本、PDB、HPA、NetworkPolicy 和 readiness。
5. `kubectl rollout status` 成功后核对实际 Deployment 镜像摘要，并归档 migration、资源和 rollout JSON 证据。

生产发布通过 GitHub Actions 的 `Production Delivery Gate` 手工触发。`production` Environment 必须配置 `KUBE_CONFIG`、两个隔离数据库 URL、`ROTATED_JWT_SECRET` 和 `ROTATED_OPERATIONS_TOKEN`，输入的 context 必须与 kubeconfig 当前 context 完全一致。

提交前可在一次性 Kind 集群执行同一套门禁；脚本使用固定隔离资源名，结束后自动清理：

```powershell
.\scripts\delivery\run-kind-delivery-drill.ps1 -KindExecutable kind
```

## 回滚

发布失败时 Workflow 停止 rollout 并执行：

```bash
kubectl -n fitpilot rollout undo deployment/fitpilot
kubectl -n fitpilot rollout undo deployment/fitpilot-web
kubectl -n fitpilot rollout status deployment/fitpilot --timeout=5m
```

门禁会核对回滚后的两个镜像是否等于发布前版本，再恢复候选摘要。数据库不执行 down migration；旧应用必须兼容已执行的新 Schema，否则停止发布并前滚修复。

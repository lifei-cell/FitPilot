# 发布与回滚

## 发布

1. PR 必须通过 Java 21 编译、单元/E2E、覆盖率、Dependency Check、Gitleaks、Trivy 和 SBOM。
2. Release Workflow 推送多架构镜像及 provenance，并归档 `release-manifest.json`；部署只接受 `image@sha256:...`。
3. 由候选镜像运行只读 `fitpilot-schema-status` Job，查询 Flyway 是否已应用 V22。候选镜像须包含此入口及 V22 迁移。探测和迁移使用独立于常驻应用的 ConfigMap。
4. V22 尚未应用，或当前后端镜像未经门禁确认兼容时，门禁先将后端缩容到 0，等待 Deployment 控制器确认且所有后端 Pod 退出。评测 Worker 也随这些 Pod 停止。正最小副本数的 HPA 会在目标规模为 0 时暂停调节。迁移成功后才替换零副本的后端模板并恢复服务。兼容版本继续走常规滚动发布。
5. `kubectl rollout status` 成功后核对 Deployment 镜像，并在后端 Deployment 记录本次验证通过的镜像。归档 schema 探测、迁移、资源和 rollout JSON 证据。

生产发布通过 GitHub Actions 的 `Production Delivery Gate` 手工触发。`production` Environment 必须配置 `KUBE_CONFIG`、两个隔离数据库 URL、`ROTATED_JWT_SECRET` 和 `ROTATED_OPERATIONS_TOKEN`，输入的 context 必须与 kubeconfig 当前 context 完全一致。

提交前可在一次性 Kind 集群执行同一套门禁；脚本使用固定隔离资源名，结束后自动清理：

```powershell
.\scripts\delivery\run-kind-delivery-drill.ps1 -KindExecutable kind
```

## 回滚

- 首次跨越 V22 或接管未验证的存量部署时，发布为前滚模式，拒绝回滚演练。迁移失败后后端保持停机。候选应用失败时保留现场，使用兼容候选镜像重跑门禁。旧镜像不兼容语料隔离，禁止手工恢复其副本或执行 `rollout undo`。
- 只有 Flyway 已应用 V22，并且发布前的后端镜像精确匹配 Deployment 上 `fitpilot.io/v22-compatible-image` 记录，门禁才允许恢复这组发布前镜像。启用 `rollback_drill` 时会验证恢复效果，再上线候选镜像。数据库迁移仍须兼容被恢复的镜像，回滚只替换镜像。
- `forwardOnly` 和 `v22AppliedBefore` 记录在 `kubernetes-rollout.json`。升级期间由同一个串行交付入口操作后端，外部 GitOps、手工扩容或其他连接同一数据库的应用须先暂停。

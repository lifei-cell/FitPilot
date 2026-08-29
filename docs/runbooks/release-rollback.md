# 发布与回滚

## 发布

1. PR 必须通过 Java 21 编译、单元/E2E、覆盖率、Dependency Check、Gitleaks、Trivy 和 SBOM。
2. Release Workflow 推送不可变 Git SHA/版本 Tag 镜像及 provenance；部署只接受该不可变 Tag。
3. 先执行 `production-migration` Job。迁移必须向前兼容，应用 Pod 禁止并发 Flyway。
4. 再应用 production overlay，确认 2 个起始副本、PDB、HPA、NetworkPolicy 和 readiness。
5. `kubectl rollout status` 成功后检查 API/Agent/LLM/RAG/Outbox Dashboard 与关键冒烟。

## 回滚

发布失败时 Workflow 停止 rollout 并执行：

```bash
kubectl -n fitpilot rollout undo deployment/fitpilot
kubectl -n fitpilot rollout status deployment/fitpilot --timeout=5m
```

数据库不执行 down migration。旧应用版本必须兼容已执行的新 Schema；若不兼容，停止发布并前滚修复。回滚后核对 Git SHA、镜像摘要、错误率、readiness、outbox 和确认令牌防重放。

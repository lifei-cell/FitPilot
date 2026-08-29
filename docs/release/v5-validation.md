# FitPilot 5.0.0 发布清单

## 本地已验证

- Maven 构建、29 个自动化测试、Flyway V1–V9、JaCoCo 门禁和 CycloneDX SBOM 生成通过。
- Agent/RAG 版本化数据集门禁通过，评测临时知识文档在任务成功前完成清理。
- Compose 应用健康启动，生产镜像以 UID/GID 10001 运行并支持只读根文件系统配置。
- 主备 LLM 与规则降级、Redis/Kafka/Elasticsearch 故障语义通过演练。
- Compose、Kustomize production、独立 migration Job 和压测脚本完成静态验证。

## 远端发布前门禁

- 在 GitHub Actions 完成 OWASP Dependency Check、Gitleaks、Trivy、SBOM/provenance 和 GHCR 多架构镜像推送。
- 在目标 Kubernetes 集群先运行 migration overlay，再验证 2 副本滚动升级、PDB、HPA、NetworkPolicy、readiness 和自动停止 rollout。
- 执行备份恢复、密钥轮换和应用版本回滚手册；确认回滚版本兼容 V9 数据库结构。
- 补跑 30 分钟混合流量和五分钟突发场景，取得完整门禁报告后再批准生产流量。

本清单不把本机清单渲染等同于真实 Kubernetes 发布，也不把一分钟预检等同于长稳容量验收。

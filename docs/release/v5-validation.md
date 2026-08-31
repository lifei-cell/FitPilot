# FitPilot 5.0.0 发布清单

## 本地已通过

- Maven 29 个自动化测试、Flyway V1–V9、JaCoCo、SBOM、Compose 与生产镜像构建通过。
- 30 分钟混合流量、5 分钟突发流量及 CPU/内存/Hikari/Kafka Lag/Outbox Age 采集通过。
- Prometheus/Grafana/Loki/Tempo/Alertmanager 在线联调、真实告警触发、服务恢复和告警解除通过。
- `prod` profile 关闭 Swagger/OpenAPI；管理端口独立为 9091，Kubernetes NetworkPolicy 仅允许 observability 命名空间访问。
- CI 增加 Prometheus/Alertmanager 校验；生产发布使用不可变 GHCR digest，归档迁移、滚动升级、readiness、回滚演练和集群证据。

## 远端阻断门禁

- GitHub Actions 完成 Gitleaks、CycloneDX SBOM/Trivy 依赖扫描、Trivy 镜像扫描、provenance 和 GHCR 多架构镜像推送。
- 真实 Kubernetes 执行 migration Job、2 副本滚动升级、PDB/HPA/NetworkPolicy/readiness、回滚与候选版本恢复。
- 在真实数据副本完成备份恢复与密钥轮换，并上传审计证据。

远端三项完成前发布结论保持 `BLOCKED`。本机清单渲染和 Compose 结果不等同于真实生产发布。

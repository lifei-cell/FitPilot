# FitPilot 5.0.0 发布清单

## 已通过

- Maven 34 个自动化测试且跳过数为 0，Flyway V1–V11、JaCoCo、SBOM、Compose 与生产镜像构建通过。
- 30 分钟混合流量、5 分钟突发流量及 CPU/内存/Hikari/Kafka Lag/Outbox Age 采集通过。
- Prometheus/Grafana/Loki/Tempo/Alertmanager 在线联调、真实告警触发、服务恢复和告警解除通过。
- `prod` profile 关闭 Swagger/OpenAPI；管理端口独立为 9091，Kubernetes NetworkPolicy 仅允许 observability 命名空间访问。
- CI 增加 Prometheus/Alertmanager 校验；生产发布使用不可变 GHCR digest，归档迁移、滚动升级、readiness、回滚演练和集群证据。
- CI Run `33352127749` 与 Release Run `33352537058` 成功；Gitleaks、CycloneDX/Trivy、应用/Web 镜像扫描、多架构 GHCR 推送、Digest Manifest、SBOM 与 provenance 均已形成远端证据。

## 真实生产门禁

- 真实 Kubernetes 执行 migration Job、2 副本滚动升级、PDB/HPA/NetworkPolicy/readiness、回滚与候选版本恢复。
- 在真实数据副本完成备份恢复与密钥轮换，并上传审计证据。

用户于 2026-08-31 决定跳过 Production Delivery Gate，因此上述真实生产门禁为 `SKIPPED`，不是 `PASS`。远端 CI/GHCR 发布已通过，但本机 Kind、清单渲染和 Compose 结果不等同真实生产上线。

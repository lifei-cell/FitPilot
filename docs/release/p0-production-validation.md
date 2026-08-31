# FitPilot P0 生产验收报告

- Run ID：`20260830-091822`
- 验证前 Git 基线：`ecbb5f81216c5107fdd7dd51de9f8dacc3355675`（P0 变更的最终提交以 Git 历史为准）
- 开始时间：2026-08-30T01:18:22.7409736Z
- 完成时间：2026-08-30T02:11:20.5978400Z
- 原始证据目录：`load-test/reports/v5-20260830-091822`（本地忽略，不提交凭据或大体积原始数据）
- 局部重跑证据：burst-rerun-20260830-100130

## 验收结论

| 门禁 | 结果 |
|---|---|
| Maven 单元测试、Testcontainers E2E、Flyway、JaCoCo、SBOM | PASS |
| 30 分钟混合流量 + 5 分钟突发流量 | PASS |
| Prometheus/Grafana/Loki/Tempo/Alertmanager 在线联调 | PASS |
| GitHub Actions、GHCR、真实 Kubernetes | BLOCKED：本机无 gh CLI、无 kube context 和生产凭据 |

只有前三项通过且远端门禁产生真实流水线/集群证据后，才能批准生产流量。

## 压测结果

| 场景 | Iterations | HTTP 请求 | HTTP 失败率 | 业务成功率 | 普通 API P95 | Agent P95 | Dropped |
|---|---:|---:|---:|---:|---:|---:|---:|
| 30 分钟混合流量 | 90000 | 118980 | 0 | 1 | 10.0268 ms | 30.6961 ms | N/A |
| 5 分钟突发流量 | 49199 | 65014 | 0.0001 | 0.9999 | 10.3934 ms | 34.6525 ms | 0 |

验收阈值：HTTP 失败率 <1%，业务成功率 >99%，普通 API P95 <250ms，Agent P95 <8s；突发场景不得出现 dropped iterations。

## 资源与一致性证据

- App 最大 CPU：188.1%
- App 最大内存占比：32.75%
- JVM Heap 最大值：181270360 bytes
- Hikari Active 最大值：4，配置上限采样值：10
- Kafka Consumer Lag 最大值：0
- Outbox Pending 最大值：26
- Outbox Oldest Age 最大值：1 秒
- App ERROR 日志条数：0
- k6 HTTP 失败数：混合场景 4，突发场景 9
- 已归档 k6 客户端错误明细条数：9
- 最终事件状态：`{"timestamp":"2026-08-30T02:07:00.8909894Z","data":{"outbox":{"PENDING":2,"SENT":9723},"openDeadLetters":0,"processedEvents":15550}}`

## 可观测性与告警证据

- prometheus-ready：PASS
- grafana-ready：PASS
- loki-ready：PASS
- tempo-ready：PASS
- alertmanager-ready：PASS
- prometheus-scrape：PASS
- loki-log-ingestion：PASS
- tempo-trace-ingestion：PASS
- prometheus-target-down-alert：PASS
- alertmanager-received-alert：PASS
- app-recovered：PASS
- target-down-alert-resolved：PASS

## 远端发布待办

1. GitHub Actions 完成 Gitleaks、CycloneDX SBOM/Trivy 依赖扫描、Trivy 镜像扫描、provenance 与 GHCR 多架构镜像推送。
2. 使用不可变镜像 Digest 执行 migration Job、2 副本滚动升级、readiness 和 PDB/HPA 验证。
3. 在真实数据副本完成备份恢复、密钥轮换和版本回滚演练，并上传流水线与集群证据。
4. 远端门禁未完成前，本报告结论保持 BLOCKED，不将本机渲染或 Compose 结果等同生产发布。

# FitPilot V5 生产验证报告

验证日期：2026-08-30。完整审计结果见 [P0 生产验收报告](../release/p0-production-validation.md)，原始证据 Run ID 为 `20260830-091822`。

## 已通过

- `mvn clean verify`：18 个单元测试、11 个 Testcontainers E2E，失败 0、跳过 0；Flyway V1–V9、JaCoCo 和 CycloneDX SBOM 通过。
- 30 分钟混合流量：90,000 iterations、118,980 HTTP 请求、失败 4（0.0034%）、业务成功率 99.99%，普通 API P95 10.03ms、Agent P95 30.70ms。
- 5 分钟突发流量：修复 k6 VU 预分配后重跑，49,199 iterations、65,014 HTTP 请求、失败 9（0.0138%）、业务成功率 99.98%，普通 API P95 10.39ms、Agent P95 34.65ms，dropped iterations 为 0。
- 资源峰值：App 容器 CPU 188.1%（多核口径）、内存 32.75%，JVM Heap 181,270,360 bytes，Hikari Active 4/10，Kafka Lag 0，Outbox Pending 26、最老 1 秒，开放死信 0。
- Prometheus、Grafana、Loki、Tempo、Alertmanager 在线联调通过；实际停止 App 触发 `FitPilotTargetDown`，Alertmanager 收到告警，App 恢复后告警解除。
- Compose、Kustomize production/migration、Prometheus 8 条规则、Alertmanager 配置和 PowerShell 脚本语法验证通过。

## 错误明细与边界

- 混合场景 4 次、突发场景 9 次失败均为本机 k6 到 Docker Desktop 主机桥接的 `dial: i/o timeout`；App ERROR 日志为 0，应用无重启，HTTP 失败率仍低于 1% 门禁。
- 原始证据保存在本机忽略目录，k6 setup JWT 已清洗；突发重跑日志单独归档。
- GitHub Actions、GHCR 和真实 Kubernetes 尚未执行：本机缺少 `gh`，`kubectl` 没有 current-context，也没有生产凭据。备份恢复、密钥轮换、真实 rollout/rollback 仍需远端证据后才能批准生产流量。

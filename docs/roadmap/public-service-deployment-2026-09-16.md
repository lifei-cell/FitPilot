# FitPilot 外网正式服务落地清单

日期：2026-09-16。本文是待执行方案；当前仓库没有真实生产集群、域名、证书或生产恢复验收证据。不要把历史 CI/GHCR、Kind 演练或本机 Compose 当成已上线。

逐项命令和首次部署的开关设置见[首次外网部署操作手册](../runbooks/first-public-deploy.md)。

## 推荐拓扑

面向应用市场和真实用户，沿用仓库现有的 GitHub Actions → GHCR 不可变镜像 → Kubernetes 迁移 Job → App/Web 滚动部署路线。入口使用云厂商受维护的 HTTPS 网关或 Gateway API 控制器，路径 `/api` 到后端、`/` 到 Web；PostgreSQL/pgvector、Redis、Kafka、Elasticsearch 保持私网可达。PostgreSQL 需要自动备份与 PITR，独立恢复库只用于演练。

预算有限且只做封闭测试时，可先用单台云服务器和专用生产 Compose 配置验证公网流程，但它是单点故障。现有 `docker-compose.yml` 公开 5432、6379、9092、9200、8080 等端口，并关闭 Elasticsearch 自身认证，**不能原样放到公网**。要单机试运行，先建立生产专用 Compose 文件、逐项检查合并结果，将数据服务仅留在内部网络，反向代理为唯一公开入口，并配置外部备份。不要把该试运行直接记为多副本生产验收。

## 执行顺序与完成证据

1. **确定地域、域名与预算**：选择服务地域和云账号，购买或使用已有域名，设定子域名、DNS A/AAAA 或 CNAME 记录、运维联系人。面向中国大陆提供互联网信息服务时，先核对网站备案及后续 APP 备案要求。产物：域名、地域、服务归属、预算上限与 DNS 解析结果。
2. **准备私网基础设施**：创建集群或服务器、私网和安全组；公网只开放 80/443，运维入口使用受控通道。创建 PostgreSQL/pgvector、Redis、Kafka、Elasticsearch，核对版本/扩展、连接加密和数据持久性；配置单独的数据库迁移账号和应用账号。产物：脱敏连接测试、资源配额、磁盘/容量阈值。不要把密码或连接串提交 Git。
3. **域名与 HTTPS**：创建受维护的网关/入口控制器，申请可自动续期的受信任证书，配置 HTTP → HTTPS、TLS 主机名、`/api` 与 `/` 路由。替换 `deploy/k8s/overlays/production/ingress-patch.yml` 的 `fitpilot.example.com`；当前文件没有 `tls`。现有 `ingressClassName: nginx` 需要核对实际控制器：社区 ingress-nginx 已退役，不应作为新集群的默认选择。产物：外部网络 `curl` 证书链、重定向、页面及 API 响应记录。
4. **生产配置与密钥**：替换 `deploy/k8s/base/configmap.yml` 中的 `.example.internal` 地址和模型占位项；配置 DB、JWT、Operations Token、镜像拉取凭据及所需 LLM/Embedding 密钥。保留 `application-prod.yml` 关闭 Swagger/OpenAPI，Refresh Cookie 设为 Secure。确认实际环境只允许必要服务互访，Metrics/管理端口不向公网开放。产物：脱敏配置清单和权限检查。
5. **按当前代码构建发布**：先让当前目标 SHA 的完整测试和 Release 通过，取得 App/Web 两个 GHCR digest；不可用 V18 的历史 digest 代替当前 V19/V20 代码。先备份并在隔离库验证恢复；停止旧版 Outbox Relay 并等在途执行结束后迁移 V20，再启用新版，遵守 `docs/release/outbox-claim-ownership.md`。完成 Flyway Job、后端与 Web 发布、readiness、镜像 digest 核对。首次部署没有上一版可回滚，不开启 rollback drill；形成稳定基线后再演练。产物：当前 SHA、镜像 digest、迁移与部署 Job 日志、健康与功能冒烟结果。
6. **监控与告警**：启用现有 Prometheus/Alertmanager/Grafana 规则，并接入真实通知接收人。至少监控外部 HTTPS 可达性、证书到期、5xx/P95、进程重启、数据库连接/磁盘、Kafka lag、Outbox 最老积压、备份失败和恢复演练结果。Grafana 与 Actuator 仅在私网/受控入口开放。产物：仪表盘截图/链接、一次主动故障告警及恢复记录。
7. **备份、恢复与发布回退**：使用托管 PostgreSQL PITR 或等价的基础备份 + WAL 归档，另做加密逻辑备份并存放到独立位置；按 `docs/runbooks/backup-restore.md` 在隔离数据库月度恢复，核对 Flyway 和业务表。目标 RPO ≤ 5 分钟、RTO ≤ 60 分钟是仓库原定目标，只有实测达到后才能宣称。应用回退只对兼容当前 Schema 的旧版本执行；数据库不做 down migration。产物：备份时间点、校验值、恢复用时和回退演练证据。
8. **手机外网验收**：不用局域网/Wi-Fi 代理，从移动数据网络走注册/登录、训练记录、离线重连、计划、Agent、退出与再次登录；覆盖证书有效、Cookie Secure、请求只走 HTTPS。记录目标 SHA、环境、设备、网络、测试结果和未解决问题。全部通过后再扩大用户范围。

## 仓库入口与当前缺口

- 发布：`.github/workflows/deploy.yml`、`scripts/delivery/run-delivery-gate.ps1`。GitHub `production` Environment 当前报告为 `SKIPPED`，缺生产 `KUBE_CONFIG`、源/隔离恢复库 URL 与轮换密钥；执行前需复核实时设置。
- 配置：`deploy/k8s/base/configmap.yml`、`deploy/k8s/overlays/production/ingress-patch.yml`、`src/main/resources/application-prod.yml`。域名、依赖端点、TLS 与受维护的入口控制器仍待落地。
- 备份/回滚：`docs/runbooks/backup-restore.md`、`docs/runbooks/release-rollback.md`。文档中的 RPO/RTO 尚无真实生产演练证明。
- 现有 `README.md` 与 `docs/release/p1-delivery-validation.md` 的 V18 远端发布证据只绑定对应历史 SHA；V19/V20 的部署顺序还需按最新变更验证。

## 官方参考（采购和部署前复核）

- [Kubernetes Gateway API 与 Ingress](https://kubernetes.io/docs/concepts/services-networking/gateway/)
- [Kubernetes 关于 ingress-nginx 退役的公告](https://kubernetes.io/blog/2025/11/11/ingress-nginx-retirement/)
- [Docker Compose 生产环境配置](https://docs.docker.com/compose/how-tos/production/)
- [PostgreSQL 17 PITR 与 WAL 归档](https://www.postgresql.org/docs/17/continuous-archiving.html)
- [Prometheus 告警与 Alertmanager](https://prometheus.io/docs/alerting/latest/overview/)
- [工信部 APP 备案通知](https://www.miit.gov.cn/jgsj/xgj/wjfb/art/2023/art_dd783a581c9644a4aee10afa582811db.html)

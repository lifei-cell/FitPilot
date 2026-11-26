# FitPilot 首次外网部署操作手册

更新：2026-09-16。目标：现有 Web 可通过手机移动网络访问，并形成可验证的生产运行、监控和恢复证据。本手册描述托管 Kubernetes 路线；`<...>` 是必须由实际云资源替换的值。执行会产生云费用并改变真实环境，按步骤核对后再进行。

## 0. 当前阻断项

- 当前 `main` 比 `origin/main` 超前；最近 V19/V20 代码没有与其精确 SHA 对应的 CI/Release/GHCR 发布证据。不得复用 V18 digest。
- `deploy/k8s/base/configmap.yml` 仍是 `.example.internal`；`deploy/k8s/base/ingress.yml` 使用 `fitpilot.example.com`、`ingressClassName: nginx`、nginx 专用 annotation 且无 TLS；两个 NetworkPolicy 仅允许 `ingress-nginx` 命名空间入口。选择新网关时，这三类配置必须一起修改和验证。
- `.github/workflows/deploy.yml` 的 `production` Environment 仍缺生产凭据（以执行时 GitHub 设置为准）。该工作流当前无论三个演练开关值如何，都会检查源/恢复数据库 URL 和轮换密钥非空，因此首次运行也要配置真实隔离库 URL 与轮换密钥。
- 首次部署必须设置 `rollback_drill=false`：尚无上一版 ReplicaSet。空业务库还不能在迁移前通过 `backup_restore_drill`：脚本会查询 `flyway_schema_history`、`users`、`training_plan`、`workout`、`outbox_event`；首次运行设 `backup_restore_drill=false`，迁移后另做恢复演练。`secret_rotation_drill` 也先设 `false`，建立运行基线后再演练。

## 1. 准备云资源、域名和网络

1. 选一个能提供托管 Kubernetes、负载均衡、受管 PostgreSQL 与密钥管理的地域；为两个应用副本准备至少两个可调度节点，并评估 Kafka、Elasticsearch、Redis、监控和数据库的持续费用。选定实际域名，例如 `app.<你的域名>`，将 DNS 指向网关的公网地址。
2. 创建私网数据库，安装/启用 `pgvector`；准备 Redis、Kafka、Elasticsearch，确认版本与本项目驱动兼容。应用和迁移 Job 必须能从集群私网访问它们。Elasticsearch 如果启用认证/TLS，需同步配置客户端访问方式；不要直接把 Compose 中关闭认证的配置搬进生产。
3. 安全组公网只开放网关 80/443。PostgreSQL 5432、Redis 6379、Kafka 9092、Elasticsearch 9200、应用 8080 和管理端口 9091 均仅允许需要的私网来源。为运维访问配置受控入口；启用节点/负载均衡的基础监控。
4. 中国大陆地域上线前核对网站 ICP 备案，未来 App 分发另核对 APP 备案；提交材料和等待时间以接入商、当地主管机关当前流程为准。

## 2. 先改仓库中的生产配置

1. 在 `deploy/k8s/base/configmap.yml` 换成真实的 DB、Redis、Kafka、Elasticsearch 地址；没有已验证真实模型时设置 `LLM_ENABLED=false` 并去掉无效模型端点。`SPRING_FLYWAY_ENABLED=false` 保持应用 Pod 不并发迁移，迁移只由 Job 执行。
2. 用云厂商受维护的 HTTPS 入口或 Gateway API 代替社区 ingress-nginx；同步修改 `deploy/k8s/base/ingress.yml`、`deploy/k8s/overlays/production/ingress-patch.yml` 和两个 NetworkPolicy。保留同一站点下 `/api` → `fitpilot`、`/` → `fitpilot-web` 的路由，确保 Web Refresh Cookie 的同站契约。为实际域名配置证书、自动续期与 HTTP → HTTPS 跳转。
3. 检查应用到外部数据服务的出口范围、网关到 App/Web 的入口策略、Metrics 采集来源；当前 App NetworkPolicy 允许 `0.0.0.0/0` 上若干数据端口，正式环境应按实际私网端点收紧。核对实际容器镜像能从 GHCR 拉取；私有包要配置 `imagePullSecrets`。
4. 将生产配置改动提交并推送到 `main`，等待当前 SHA 的 CI 和 Release 成功，记录两个不可变 `sha256:` digest。检查 Release Manifest 中的 SHA 与部署目标一致。切勿使用 `main` 浮动标签部署。

在仓库根目录核对发布对象；`<Release Run ID>` 来自成功的 Release Workflow，下载后的 JSON 中 `revision` 必须等于要部署的提交 SHA：

```powershell
git rev-parse HEAD
gh run list --workflow ci.yml --branch main --limit 5
gh run list --workflow release.yml --branch main --limit 5
$releaseRunId = 12345678 # 换成成功的 Release Run ID
gh run download $releaseRunId --name "ghcr-digests-$releaseRunId" --dir release-evidence/current
Get-Content release-evidence/current/release-manifest.json
```

## 3. 创建命名空间和密钥

先在终端确认目标上下文，不要把真实密钥粘贴进命令历史、仓库、Issue 或聊天记录：

```powershell
$kubeContext = "fitpilot-production" # 换成实际上下文
kubectl config current-context
kubectl --context $kubeContext get nodes
kubectl --context $kubeContext apply -f deploy/k8s/base/namespace.yml
kubectl --context $kubeContext auth can-i create jobs -n fitpilot
kubectl --context $kubeContext auth can-i patch deployments -n fitpilot
```

通过云 Secret Manager/External Secrets 或受控一次性安全流程创建 `fitpilot/fitpilot-secrets`，至少有 `DB_PASSWORD`、不少于 32 字节的 `JWT_SECRET`、随机 `OPERATIONS_TOKEN`；启用真实 LLM/Embedding 再加入对应密钥。Kubernetes Secret 的普通 base64 编码不是加密存储，须同时开启集群静态加密和最小 RBAC。`production` GitHub Environment 配置 `KUBE_CONFIG`、`DELIVERY_SOURCE_DATABASE_URL`、`DELIVERY_RESTORE_DATABASE_URL`、`ROTATED_JWT_SECRET`、`ROTATED_OPERATIONS_TOKEN`。后两项即使首次不开启轮换，当前预检也要求非空。源/恢复 URL 使用 `postgresql://...`，指向不同数据库；恢复库必须可被演练覆盖，绝不能指向业务库。

## 4. 首次部署

1. 先验证外部 PostgreSQL 有空库和扩展，网络可连；如果数据库已有旧版 FitPilot 数据，按 V19/V20 迁移与 Relay 停机要求制定升级窗口，不能套用下面的空库首次流程。
2. 在 GitHub Actions 手工运行 `.github/workflows/deploy.yml`，输入本次 Release 的两个 digest、精确 `kube_context`；首次选择 `rollback_drill=false`、`backup_restore_drill=false`、`secret_rotation_drill=false`、`revoke_previous_jwt=false`。`production` Environment 的审批由账号所有者完成。
3. 工作流执行 Flyway migration Job，再应用生产 overlay，等待 App/Web readiness 并核对部署镜像 digest。下载并保留该 Run 的 `production-delivery-<run-id>` 证据，检查 `migration.log`、`kubernetes-rollout.json`、Pod/Ingress/NetworkPolicy 及事件。
4. 从外部网络检查 DNS、证书和页面，执行注册/登录、计划、训练开始/记录/完成、退出再登录的冒烟测试。管理端口与数据库端口从公网应无法直接访问。`/api` 不应返回 Web 首页；浏览器网络面板应只看到 HTTPS API 请求。

```powershell
$appHost = "app.example.com" # 换成实际域名
Resolve-DnsName $appHost
curl.exe -I "https://$appHost/"
kubectl --context $kubeContext -n fitpilot get deploy,pods,svc,ingress
kubectl --context $kubeContext -n fitpilot rollout status deployment/fitpilot --timeout=5m
kubectl --context $kubeContext -n fitpilot rollout status deployment/fitpilot-web --timeout=3m
```

## 5. 补全恢复、告警与常规发布

1. 为受管 PostgreSQL 开启自动备份与 PITR/WAL 归档，配置独立存储的加密逻辑备份和失败告警。迁移完成后先在隔离恢复库运行 `scripts/delivery/prepare-database-drill.ps1`、`run-backup-restore-drill.ps1`；核对 Flyway、关键业务表、备份 SHA-256 和恢复耗时。以后每月演练，形成真实 RPO/RTO 证据。仓库原定 RPO ≤ 5 分钟、RTO ≤ 60 分钟，当前不能当作实测结果。
2. 部署 Prometheus/Alertmanager/Grafana，接入真实通知接收人；验证目标不可达、5xx、P95、数据库连接/磁盘、Kafka lag、Outbox 最老积压和备份失败。Grafana/Actuator 仅在受控网络暴露；通过一次可恢复的故障测试确认通知送达。
3. Flyway 已应用 V22 且发布门禁已为当前后端镜像记录兼容性后，后续发布可开启 `rollback_drill=true`。确认恢复目标兼容本次数据库迁移。单独演练密钥轮换和旧凭据撤销。生产发布、备份与回滚记录绑定每次 SHA、镜像 digest、环境与时间。见[发布门禁](release-rollback.md)。
4. 使用手机移动数据而非同一局域网访问，验证登录、训练离线记录与重连同步、锁屏/切后台、Agent 降级、账号切换。记录设备、系统、网络、结果和未解决问题；通过后再扩大真实用户。

## 官方参考

- [Kubernetes Ingress 与 Gateway API](https://kubernetes.io/docs/concepts/services-networking/gateway/)
- [ingress-nginx 退役公告](https://kubernetes.io/blog/2025/11/11/ingress-nginx-retirement/)
- [PostgreSQL PITR/WAL 归档](https://www.postgresql.org/docs/17/continuous-archiving.html)
- [Prometheus 告警](https://prometheus.io/docs/alerting/latest/overview/)

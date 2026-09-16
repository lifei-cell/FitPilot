# 现有 ECS 部署前检查

记录日期：2026-09-16。依据用户提供的终端截图；这是当时的主机快照，后续变配或重装后需重新检查。此文档不记录公网 IP、主机名或凭据。

## 已观察到

- 系统：CentOS Linux 7，x86_64，2 vCPU、约 1.7 GiB RAM、1 GiB swap、40 GiB 根磁盘（截图时可用约 33 GiB）。
- Docker 与 Docker Compose 尚未安装；SSH 正常。宝塔面板监听 8888，SSH 监听 22；其余监听端口也需核对云安全组，而不是只依据进程监听状态判断公网可达。
- CentOS Linux 7 已结束维护，当前 Docker 官方安装前提为维护中的 CentOS Stream 9/10。现有主机不宜直接安装最新版 Docker 后承载公开服务。
- 当前 `docker-compose.yml` 设定应用最高 2 GiB、Kafka 2 GiB、Elasticsearch 2 GiB、PostgreSQL 1 GiB、Redis 1 GiB，加上 Web/系统开销；这些是容器上限而非固定用量，但完整堆栈在 2 GiB 主机上有很高的 OOM 风险。

## 下一步

1. 用户已确认本机没有需要保留的宝塔网站、数据库或文件。到 ECS 控制台核对实例 ID、系统盘和计费方式；建议先创建并确认系统盘快照，再开始变配/换镜像。阿里云文档说明更换系统盘镜像会清除原系统盘数据。
2. 在实例详情页的“全部操作 → 更改实例规格”查看同可用区可选规格和实际价格。若坚持单机运行完整堆栈，以至少 4 vCPU/16 GiB RAM 为初始规划；40 GiB 系统盘空间较紧，按实际镜像、数据与备份增长规划扩至约 80 GiB 或配置独立数据盘。完成升配并按控制台要求重启。
3. 在实例详情页选择“全部操作 → 更换操作系统 → 更换系统盘”，选择云平台提供的 Ubuntu 24.04 LTS 公共镜像与 SSH 密钥/新凭据。按页面指引正常停止实例，按量付费场景优先普通停机模式；核对新系统盘和费用后再执行。此操作会清除旧系统盘，包括宝塔。换系统后重新连接并核对 SSH 主机指纹。
4. 用 `cat /etc/os-release`、`nproc`、`free -h`、`df -h /` 核对新系统和实际资源。单机仍有停机和数据丢失的单点故障；正式多用户服务更适合应用节点与私网托管数据服务分离。确认配置后再按 Docker 官方仓库方法安装 Engine 与 Compose 插件。
5. 在安全组中仅对本人可信 IP 开放 SSH 22；旧宝塔不再使用后关闭 8888 公网规则。网站启用时才对公网开放 80/443；数据库、Redis、Kafka、Elasticsearch 和应用管理端口不向公网开放。

## 官方参考

- [CentOS Linux 7 生命周期](https://www.centos.org/centos-linux/)
- [Docker CentOS 安装前提](https://docs.docker.com/engine/install/centos/)
- [Docker Ubuntu 支持版本](https://docs.docker.com/engine/install/ubuntu/)
- [阿里云更换 ECS 系统盘镜像说明](https://www.alibabacloud.com/help/en/ecs/faq-about-replacing-the-operating-system-of-an-instance)
- [阿里云创建系统盘快照](https://www.alibabacloud.com/help/en/ecs/user-guide/create-a-snapshot)
- [阿里云更改实例规格](https://help.aliyun.com/zh/ecs/user-guide/change-the-instance-types-of-subscription-instances)
- [阿里云更换操作系统](https://help.aliyun.com/zh/ecs/user-guide/replace-the-operating-system-of-an-instance)

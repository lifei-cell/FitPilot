# 多实例展示缓存采用短 TTL

日期：2026-09-13。

## 决策与改动

展示允许短暂不一致，沿用 Cache Aside 与事务提交后删除本实例 L1、共享 L2。跨实例依靠过期收敛，不增加失效消息或版本化缓存系统。

| 配置 | 原默认值 | 新默认值 |
|---|---:|---:|
| `CACHE_L1_TTL_SECONDS` | 300 | 5 |
| `CACHE_L2_TTL_SECONDS` | 1800 | 30 |
| `CACHE_L2_TTL_JITTER_SECONDS` | 300 | 5 |

保留 Caffeine `expireAfterWrite`、容量限制、Redis 抖动、重建锁、空值缓存和故障回源。三个参数同步到 `.env.example` 与 Compose 环境变量透传；用户显式设置的 TTL 仍优先。部署到多实例时，应全部更新配置并重启应用；已有 Redis 数据保留写入时的旧 TTL，不能宣称发布瞬间所有条目均已切换为新 TTL。

动作详情、用户资料与 ACTIVE 计划读取沿用展示缓存。开始训练使用 `workoutTemplate` 直接读取数据库计划和 owner；确认 AI 调整时新增 `isCurrentActiveVersion`，只从数据库读取当前用户 ACTIVE 计划并比较 ID、版本，不再使用展示缓存。不存在 ACTIVE 计划也视为过期建议并拒绝确认。

## 一致性边界与成本

- L1 命中不续期，L2 命中也不续期。正常删除 L2、没有并发旧读回填时，其他实例已缓存旧值在其 L1 剩余 TTL 内过期。
- 删除 L2 失败或并发查询回填旧值时，L1 可能重新加载旧 L2。从最后一次旧值写入 L2 起，无后续旧值回填时，默认自然过期窗口最多约 40 秒（30 + 5 + 5）；这不是从数据库事务提交起算的严格时限。
- TTL 不解决读完数据库后又发生并发修改的 TOCTOU 问题。本次确认校验消除缓存导致的旧读，没有新增跨整个确认事务的计划锁或串行化协议。
- TTL 缩短会增加 Redis 读取和数据库重建频率；现有历史性能数字不能当作本配置下的新容量结论。
- 测试使用两个独立 L1 对象共享真实 Redis，模拟多实例缓存拓扑，不宣称执行了两个生产应用进程或生产部署。

## 验证

- `PerformanceConfigTest`：从实际 `application.yml` 绑定默认配置，检查写入后过期策略及 L1/L2 TTL。
- `TwoLevelCacheIT`：使用真实 Redis 与生产 Caffeine 工厂，缩短测试 TTL 为 L1 1 秒、L2 2 秒；覆盖写实例失效后的其他实例旧读收敛、持续访问不续期、L2 删除失败后的自然收敛。
- `TrainingPlanFreshnessTest`：数据库版本变化、计划 ID 变化、用户没有 ACTIVE 计划，以及绕开展示缓存。
- `TrainingAdjustmentServiceTest`：确认拒绝过期版本、确认通过后创建草案，均不访问缓存型 ACTIVE 查询。

首次沙箱内 `mvn verify` 无法访问 Docker，22 个集成测试跳过，零跳过门禁失败；该运行不作为通过证据。允许访问 Docker 后，设置 `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` 重跑 `mvn verify`，于 2026-09-13 11:28（Asia/Shanghai）取得 BUILD SUCCESS：64 项单元测试、22 项集成测试，共 86 项，零失败、零错误、零跳过；Flyway V1–V19、覆盖率与零跳过门禁通过。新增两个真实 Redis 缓存场景全部通过。

`docker compose config --quiet` 与 `git diff --check` 通过。日志位于本机忽略目录 `target/short-ttl-verify.log`，XML 报告在 `target/surefire-reports` 和 `target/failsafe-reports`。本次没有改动前端，没有执行新压测、远端 CI 或部署；现有环境的缓存配置需要发布后才生效。

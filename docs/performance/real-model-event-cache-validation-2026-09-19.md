# 真实模型、双实例事件恢复与缓存收益验收

日期：2026-09-19。被测工作区基线为 `5cd23a3e8ccaad291f4cba4d6cdf89968646057a`；脚本和 Compose 文件的 SHA-256 写入各自的本地运行 manifest。三项结果分开判定，不能用局部实验替代真实生产门禁。

## 结果总览

| 项目 | 状态 | 结果 |
|---|---|---|
| 真实模型质量 | `BLOCKED_EXTERNAL_PROVIDER` | 只完成不外发数据的前置检查；未调用外部模型，不能给出质量、延迟或成本结论。 |
| 双实例事件恢复 | `PASS` | 两个独立 Spring Boot 容器共享 PostgreSQL/Redis/Kafka/Elasticsearch；app-a 在 Outbox 领取后停止，Kafka 恢复后由 app-b 追平。 |
| 缓存收益对照 | `PASS（局部收益）` | 热点 L1 命中有稳定局部收益；冷读增加成本，不能写成所有请求都变快。 |

## 1. 真实模型质量

入口：`scripts/evaluation/run-real-model-quality.ps1`。脚本先检查 `LLM_ENABLED`、主 Provider URL/API Key/模型名和运维 Token，再调用现有 `run-evaluations.ps1 -AgentMode ACTIVE_MODEL`；若最终 `model` 为 `RULE_WORKFLOW`，会拒绝把规则兜底当成真实模型通过。

本次只执行了无外发的 preflight，结果见[manifest](evidence/real-model-preflight-20260919/manifest.json)。工作区 `.env` 虽配置了 Provider 字段，但实际调用会把项目评测样本发送到外部地址，当前没有得到该数据外发的明确授权，因此没有执行。GitHub 的真实模型工作流仍可在已授权的受控环境中运行，并上传脱敏评测报告。

授权后重跑时，必须同时保留 `agent-evaluation.json`、`rag-evaluation.json`、Provider 返回的实际模型名、Prompt 版本、数据集散列和费用/延迟；规则模式、Mock Provider、LOCAL Embedding 不得混入真实模型结论。

## 2. 双实例事件恢复

入口：`scripts/operations/run-dual-instance-event-recovery.ps1 -RunId <name>`，拓扑由 `docker-compose.dual-instance.yml` 提供。运行 `dual-20260919-r3` 的关键护栏如下：

- Kafka 和 app-b 停止期间通过 app-a 完成一条真实训练，Workout 与 Outbox 同事务提交；确认 app-a 已领取事件后停止 app-a。
- Kafka 与 app-b 恢复后，app-b 在 3 秒领取租约过期后重领；Outbox 最终为 `SENT`，待处理事件为 `0`。
- 两个 Workout 事件消费者均已处理（`processedConsumers=2`）；Personal Record `3` 条、Analytics `1` 条、Notification `3` 条，业务重复行 `0`。

完整摘要见[双实例证据](evidence/dual-instance-events-20260919-r3/manifest.json)。这是一次本地 Docker、单条完成请求和受控实例停止演练，证明共享 Outbox 的恢复与幂等护栏；不证明生产容量、长时间积压 SLO 或 Exactly Once。

## 3. 缓存收益对照

入口：`scripts/performance/run-architecture-comparison.ps1 -RunId architecture-20260919-followup`；三路径使用同一 PostgreSQL 表、同一 payload、两个独立 L1 对象和本地 Redis，热/冷各 3 轮、每轮 600 次。

| 场景 | PG 直读 P95（ms） | Redis 单层 P95（ms） | L1+L2 P95（ms） | 解释 |
|---|---:|---:|---:|---|
| 热点 | 0.5964 / 0.6040 / 0.5880 | 0.8635 / 0.5855 / 0.5623 | **0.0052 / 0.0021 / 0.0015** | L1 命中 600/600，数据库 loader 为 0。 |
| 冷读 | **0.5564 / 0.6121 / 0.5135** | 3.3707 / 2.8510 / 2.6491 | 3.2185 / 2.7858 / 2.7970 | 三路均回源 600 次，缓存路径承担网络、锁和回填成本。 |

跨缓存对象更新后，另一对象约 `5052.7551 ms` 观察到新值，符合当前 5 秒 L1 TTL 加轮询的局部收敛观察。该数字不是全局陈旧窗口上界。原始摘要见[cache-summary](evidence/architecture-20260919-followup/cache-summary.json)。

决定：保留两级缓存用于高复用展示读；写前校验和关键事实继续直读 PostgreSQL；后续若要宣称缓存必要性，还需 HTTP 多实例并发、Redis 故障峰值、DB QPS/CPU 和资源成本证据。

## 复现入口

- 双实例事件恢复：`./scripts/operations/run-dual-instance-event-recovery.ps1 -RunId <new-run>`。
- 缓存三路径：`./scripts/performance/run-architecture-comparison.ps1 -RunId <new-run>`，只读取该 run 的 `cache.json`。
- 真实模型：仅在明确授权 Provider 与评测数据外发后，使用 `./scripts/evaluation/run-real-model-quality.ps1 -EnvFile .env -ReportDirectory <dir>`。

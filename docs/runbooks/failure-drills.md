# 故障演练

## 执行前

仅在隔离环境执行。使用与 Compose 中一致的 Operations Token，确保 Docker 服务健康，确认没有真实用户流量。

```powershell
docker compose --env-file .env.example up -d --wait
./scripts/invoke-failure-drills.ps1 -OperationsToken change-me-operations-token -IncludeLlm
```

脚本按顺序验证：Redis 中断时 Readiness 保持 `UP`，并在 15 秒预算内完成 50 次 PostgreSQL 回源；Kafka 中断时业务提交与 outbox 补发；Elasticsearch 中断时 `VECTOR_ONLY` 检索；主 LLM 中断时备用模型接管；双 LLM 中断时 `RULE_WORKFLOW` 接管。每个基础设施服务都在 `finally` 中恢复。

Redis 被定义为软依赖：缓存、限流和短期会话允许降级，PostgreSQL 始终保存业务真相，因此 Redis Health 不进入 Readiness Group。故障窗口仍需观察数据库连接池、查询 P95、错误率与本地限流指标；若回源流量超过数据库容量，应限流或降载，而不是把 Redis 临时升级成业务真源。

LLM 调用共享单次全链路 Deadline，并由 Bulkhead 限制并发。演练时还要确认 `Retry-After` 不超过配置上限、Deadline 到期后请求被取消、熔断器只允许一个半开探测请求，避免主备串行重试耗尽 Web 请求线程。

演练后检查：所有服务 healthy、outbox pending=0、dead letter 未增长、LLM fallback 指标符合故障窗口、应用 trace 不包含问题正文或 Tool 输出。使用 `docker compose --profile fault down` 清理临时容器；除非获得备份确认，不加 `--volumes`。

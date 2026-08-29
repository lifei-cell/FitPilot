# 故障演练

## 执行前

仅在隔离环境执行。使用与 Compose 中一致的 Operations Token，确保 Docker 服务健康，确认没有真实用户流量。

```powershell
docker compose --env-file .env.example up -d --wait
./scripts/invoke-failure-drills.ps1 -OperationsToken change-me-operations-token -IncludeLlm
```

脚本按顺序验证：Redis 中断时数据库回源；Kafka 中断时业务提交与 outbox 补发；Elasticsearch 中断时 `VECTOR_ONLY` 检索；主 LLM 中断时备用模型接管；双 LLM 中断时 `RULE_WORKFLOW` 接管。每个基础设施服务都在 `finally` 中恢复。

演练后检查：所有服务 healthy、outbox pending=0、dead letter 未增长、LLM fallback 指标符合故障窗口、应用 trace 不包含问题正文或 Tool 输出。使用 `docker compose --profile fault down` 清理临时容器；除非获得备份确认，不加 `--volumes`。

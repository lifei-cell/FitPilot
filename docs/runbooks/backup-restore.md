# 数据备份与恢复

## 目标

PostgreSQL 是业务与审计真相源；Redis 可重建，Kafka/Elasticsearch 由托管平台按其快照策略保护。生产数据库必须开启 PITR，RPO 不高于 5 分钟、RTO 不高于 60 分钟。

## 备份

1. 每日生成加密的逻辑备份，并由托管 PostgreSQL 持续归档 WAL。
2. 备份任务使用只读专用账号，备份桶启用版本控制、不可变保留和跨区域复制。
3. 每次备份记录数据库版本、Flyway 版本、Git SHA、对象校验和与恢复截止时间。
4. 每月在隔离环境执行恢复演练；校验 `flyway_schema_history`、用户/计划/训练/outbox/审计表行数和抽样业务查询。

门禁要求源库与恢复库 URL 不同，URL 只写入临时 `fitpilot-delivery-drill` Secret，不进入参数日志。恢复 Job 使用 `pg_dump` custom format 和 `pg_restore --exit-on-error`，对关键表行数、Flyway rank 和备份 SHA-256 做强校验；任何一项不一致即失败，绝不覆盖源库。

```powershell
.\scripts\delivery\prepare-database-drill.ps1 `
  -KubeContext fitpilot-production `
  -SourceDatabaseUrl $env:DELIVERY_SOURCE_DATABASE_URL `
  -RestoreDatabaseUrl $env:DELIVERY_RESTORE_DATABASE_URL
.\scripts\delivery\run-backup-restore-drill.ps1 -KubeContext fitpilot-production
```

## 恢复

1. 冻结写流量，记录事故时间点，创建新数据库实例，禁止覆盖原实例。
2. 恢复到事故前目标时间，运行 `flyway validate`，不得执行向后迁移。
3. 用迁移 Job 将新实例推进到当前兼容版本，再运行只读冒烟和数据一致性检查。
4. 切换应用 Secret 中的 `DB_URL`，滚动发布；确认 readiness、错误率、outbox 最老积压和 Kafka lag。
5. 保留旧实例至少一个完整观察窗口，完成审计后再按变更流程释放。

Redis 恢复失败时允许冷缓存启动；Elasticsearch 通过 PostgreSQL 知识块重新索引。Kafka 恢复后由 outbox 自动补发，禁止手工修改 `PUBLISHED` 状态。

审计证据为 `backup-restore.log` 和脱敏后的 Job JSON；完成组合门禁后临时数据库 Secret 会被删除。

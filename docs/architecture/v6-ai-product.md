# V6 AI 产品价值闭环

## 持久化会话

Agent 会话和消息以 PostgreSQL 为真源，Redis 只缓存最近 30 条消息。Web 提供会话列表、历史分页、重命名、归档、删除和跨设备待确认动作恢复；Redis 故障不影响历史读取。默认保留 180 天，清理任务跳过仍存在有效待确认动作的会话。

## 可解释计划调整

Workout 完成后可提交疲劳 1-10、疼痛 0-10 和备注。`TrainingAdjustmentService` 使用最近 28 天训练事实计算计划完成率、Set 完成率、平均工作组 RPE、疲劳、疼痛、前后 14 天容量变化和 PR 数量。

- 数据少于 3 次完成训练或 2 份反馈时不生成草案。
- 最近疼痛达到 4 时进入 `SAFETY_HOLD`，不允许自动进阶。
- 高疲劳或高 RPE 只允许 10%-20% 减量；低完成率最多减少一个训练日；恢复良好的平台期最多增加 10% 周组数。
- Agent 使用 `get_training_adjustment_context` 读取服务端指标，仅通过 `adjust_training_plan` 创建待确认动作。
- 确认时重新校验源 ACTIVE 计划 ID 和版本，成功后只创建新的 DRAFT；源 ACTIVE 计划保持不变。

调整证据、规则、原因、模型、Prompt、待确认动作和最终草稿均写入 `plan_adjustment`，支持用户拒绝和审计。

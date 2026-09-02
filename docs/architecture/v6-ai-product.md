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

## AI 产品价值指标

`AgentProductMetricsService` 直接从会话、执行审计、调整决策和训练事实聚合，不依赖前端埋点。Operations API 支持 14-365 天观察窗口与 7-90 天结果窗口；默认每 5 分钟发布低基数 Prometheus Gauge，并由 `FitPilot AI Product Value` Dashboard 展示。

| 指标 | 稳定定义 |
|---|---|
| D7 会话留存率 | 首次发送 Agent 消息后的第 1-7 个自然日再次发送消息；未满 7 天不进入分母 |
| 建议接受/拒绝率 | 分别为 `ACCEPTED`、`REJECTED` 除以两者之和；未决建议不污染决策偏好 |
| 确认转化率 | `ACCEPTED / proposal 非空的建议数` |
| 规则降级率 | `model=RULE_WORKFLOW` 的执行数 / 全部 Agent 执行数 |
| 单次成功成本 | 窗口内执行总成本 / `SUCCEEDED + AWAITING_CONFIRMATION` 执行数 |
| 训练调整收益 | 以已接受草案的激活日为锚点，对比等长前后窗口的完成率、疼痛反馈、训练容量和 PR；只纳入后窗口已完整结束的调整 |

返回值包含 Prompt、模型、业务意图分组，以及最多 100 个纳入结果窗口的 `plan_adjustment` ID，能够回查原始证据。疼痛均值同时返回反馈样本数，空样本不会被解释为零疼痛。训练结果是前后窗口的观察性证据，不等价于因果结论；模型切换仍需离线评测与小流量随机对照。

Dashboard 告警设置了最小样本边界：Agent 执行或建议至少 20 条后评估降级、转化和成本，训练调整至少 10 条后评估疼痛与完成率回归。

## RAG 治理与反馈评测

`knowledge_document` 保存当前版本及 `ACTIVE/EXPIRED/REVOKED/DELETE_PENDING/DELETED` 生命周期；
`knowledge_document_revision` 保存不可变版本。相同 `externalId` 摄取时版本单调递增，恢复旧内容也会创建新版本。
已有文档统一迁移为 `COMMUNITY`，在线重排仅给予 `OFFICIAL=1.00`、`INTERNAL=0.90`、
`PROFESSIONAL=0.85`、`COMMUNITY=0.60` 的有限加分，不覆盖 BM25、向量和 RRF 相关性。

- PostgreSQL 向量查询和 Context 回填均硬过滤失效生命周期和有效期；Elasticsearch 同时过滤生命周期与过期时间。
- 删除先事务性标记 `DELETE_PENDING` 并移除 PostgreSQL Chunk，确保立即不可检索；ES 删除由任务表重试，成功后清空正文并保留最小审计字段。
- Citation 返回文档 ID、发布方、可信等级、版本和有效期；每次检索生成 `retrievalId` 和结果快照。
- 用户可对回答或单个 Citation Upsert `HELPFUL/NOT_HELPFUL`。反馈不会直接改变在线排序，只有 Operations 审核并填写正确来源后才进入动态评测集。
- 每次评测冻结静态数据集版本和已审核动态样本版本，输出总体及分类 Recall@5、MRR 和 Citation Validity；引用有效率不是 100%，或任一分类相对最近成功基线下降超过 5 个百分点时，评测失败。

V12、V13、V14、V15 均为向前迁移。V15 仅增加产品指标查询索引。部署必须按“Flyway 迁移 → 后端 → Web”执行；旧消息接口在新 Web 上线前继续保留。

## API 示例

```http
PUT /api/v1/workouts/42/feedback
{"fatigueScore":8,"painScore":1,"notes":"睡眠不足"}

PUT /api/v1/rag/retrievals/{retrievalId}/feedback
{"targetType":"CITATION","targetKey":"{documentId}","rating":"NOT_HELPFUL","reason":"WRONG_CITATION"}

PUT /api/v1/operations/rag/feedback/{feedbackId}/review
X-Operations-Token: ***
{"decision":"APPROVED","reviewer":"ops","correctSourceUrls":["https://publisher.example/guide"]}

GET /api/v1/operations/agent/product-metrics?windowDays=90&outcomeWindowDays=28
X-Operations-Token: ***
```

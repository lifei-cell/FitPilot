# V4 单 Agent 与 Workflow 架构

## 范围

V4 采用一个 Agent、确定性意图路由和显式 Workflow，不做 Multi-Agent。默认 `RULE_WORKFLOW` 可离线运行，后续接入模型网关时也不得改变后端授权边界。

## Tools 与权限

只读工具为 `get_user_profile`、`get_workout_history`、`get_personal_records`、`get_training_plan`、`get_training_volume`、`search_knowledge`。工具参数没有 `userId`，执行器只使用 JWT `CurrentUser.id`，并复用各领域 Service/Repository 的 owner 查询。

`create_training_plan` 是两阶段写工具：

1. 反序列化为 `TrainingPlanDtos.CreateRequest`。
2. 执行计划领域规则和 Agent Guardrail（周期、频率、动作数、组数、次数、RPE、休息、周总组数）。
3. 保存 `AWAITING_CONFIRMATION` 待执行动作，只返回一次明文确认令牌，数据库仅存 SHA-256。
4. 当前 owner 使用未过期令牌确认；事务内重新校验并调用 `TrainingPlanService.create`，计划只保存为 `DRAFT`。
5. 令牌不可跨用户使用且不可重放。

## Memory 与审计

- PostgreSQL `agent_message` 是会话正文真源，默认保留 180 天；Redis key `agent:session:{sessionId}` 仅缓存最近 30 条消息、TTL 2 小时。
- Redis 未命中或故障时回源 PostgreSQL；升级前仅在 Redis 中的近期消息在首次读取时懒迁移。
- 会话列表、历史游标分页、重命名、归档和删除均使用 JWT Owner 校验；浏览器仅保存当前会话 ID。
- PostgreSQL `agent_memory`：按 `(user_id, memory_key)` 保存 JSONB 长期偏好；`weekly_frequency` 会直接约束无现有计划时的草案频率。
- PostgreSQL `agent_execution`：意图、选择工具、期望工具标签、状态、模型、时延、违规数。
- PostgreSQL `agent_tool_call`：请求、响应、状态和时延；MCP 调用也进入相同审计链路。

## MCP

`POST /mcp` 是最后一层薄适配器，使用现有 Bearer JWT 认证并支持 `server/discover`、`tools/list`、`tools/call`、`resources/list`、`resources/read`。协议版本固定为 `2026-07-28`，校验 `MCP-Protocol-Version`、`Mcp-Method` 与 JSON-RPC body；写工具返回 `input_required`，仍由同一确认 API 完成持久化。协议版本依据 [MCP 2026-07-28 官方发布说明](https://blog.modelcontextprotocol.io/posts/2026-07-28/)。

## 验收指标

`GET /api/v1/operations/agent/metrics` 返回：

- Task Success Rate：`SUCCEEDED` 或 `AWAITING_CONFIRMATION` 的执行比例。
- Rule Violation Rate：被规则拦截的执行比例。
- Tool Selection Accuracy：对已通过运维接口标注 `expected_tools` 的执行做严格有序匹配。

单元测试内置中文意图数据集验证工具选择；集成测试验证未确认零写入、跨用户确认失败、确认后仅创建草稿、重放拒绝、工具审计和 MCP 身份继承。

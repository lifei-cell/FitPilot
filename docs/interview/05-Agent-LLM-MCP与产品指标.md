# Agent、LLM Gateway、MCP 与 AI 产品指标

## 1. Agent 与普通 Chatbot 的区别

普通 Chatbot 主要生成文本；Agent 会根据目标选择 Tool、读取环境状态并推进 Workflow。只要能调用 Tool，就出现了权限、参数、重复执行、确认、审计和错误恢复问题。

FitPilot 不使用自由循环的 Multi-Agent，而是一个受控单 Agent + 显式 Workflow：

```text
Intent → Owner-scoped Read Tools → Structured Proposal
       → Schema / Domain Validation → Guardrail
       → Pending Action → User Confirmation → DRAFT Persistence
```

这样牺牲部分“自主性”，换取可预测、可测试和可审计，适合健身计划这种可能影响用户训练安全的写操作。

## 2. Tool Calling 的安全边界

### 项目原则

- LLM 不能决定当前用户身份。
- Tool 参数不接受 `userId`，统一继承 JWT `CurrentUser.id`。
- 只开放领域 Tool，不开放任意 SQL、Shell、HTTP 代理或文件系统。
- 只读 Tool 与写 Tool 分离。
- 写 Workflow 必须完整，不能只返回 `create_training_plan` 绕过前置读取。
- Tool 输入反序列化为强类型 DTO，再做 Bean Validation、领域规则和 Guardrail。

`LlmGateway.validate` 还会递归拒绝任何参数层级中的 `userId`，并校验 Tool 名称白名单与写 Workflow 顺序。

### 高频追问

**问：模型都输出 JSON 了，为什么还要校验？** JSON 只保证可解析，不保证业务合法、权限正确或没有危险参数。结构化输出是校验入口，不是信任证明。

**问：为什么不让模型直接调用 Repository？** Repository 接近数据边界，容易绕过 Owner、状态机、事务和审计。Tool 应复用 Application Service 能力。

## 3. Guardrail 与领域规则

Guardrail 是确定性安全规则，例如训练周期、频率、动作数量、组数、次数、RPE、休息时间和周总组数。它与模型 Safety Prompt 的区别：

- Prompt 是概率性约束，模型可能忽略或误解。
- Guardrail 是服务端可测试的确定性拒绝。
- 领域规则负责业务有效性；Guardrail 额外约束 AI 生成的风险范围。

FitPilot 训练调整还加入数据门槛和疼痛规则：完成训练少于 3 次或反馈少于 2 份不生成草案；最近疼痛达到 4 进入 `SAFETY_HOLD`；高疲劳/RPE 只允许减量；确认后只创建新 DRAFT，不覆盖 ACTIVE。

**追问：规则会不会太保守？** 会，所以要把拒绝原因、样本和转化率纳入指标，通过离线评测和产品实验调整；但安全边界不能由模型自行放宽。

## 4. Human-in-the-loop 确认协议

写 Tool 不直接落库，而是保存 Pending Action：

1. 后端生成高熵一次性 Token。
2. 只把明文返回一次，数据库保存 SHA-256。
3. Token 绑定 User、Action 和过期时间，默认 10 分钟。
4. 当前用户提交 Token 后用常量时间方式比较摘要。
5. 事务中重新加载最新事实、重新校验，再执行写入。
6. 成功后标记已消费，重放、过期和跨用户确认均拒绝。

### 为什么确认时要重新校验

提议和确认之间，ACTIVE 计划版本、动作状态或用户数据可能变化。只校验一次会产生 TOCTOU（检查时与使用时）问题。训练调整确认还会核对源计划 ID 和 Version。

### 为什么只存 Token Hash

数据库泄漏不能直接获得可用确认凭据；逻辑类似 Refresh Token 哈希。由于 Token 高熵，SHA-256 足够用于摘要匹配；用户密码则需要 BCrypt/Argon2 这类慢哈希抵抗字典攻击。

## 5. Agent Memory

### 短期消息

- PostgreSQL `agent_message` 是正文真源，默认保留 180 天。
- Redis List 只缓存最近 30 条，TTL 2 小时。
- Redis 故障时回源 PostgreSQL，并可回填缓存。
- 会话列表、游标分页、重命名、归档和删除均带 Owner 条件。

### 长期偏好

`agent_memory(user_id, memory_key, value JSONB)` 保存如每周训练频率等稳定偏好。长期记忆不能无限自动抽取：应有来源、更新时间、用户可见/可删和冲突策略，敏感信息尤其要谨慎。

### 为什么不把全部历史放 Prompt

Context Window、Token 成本和注意力稀释都会恶化。合理做法是近期窗口 + 结构化长期偏好 + 必要事实检索；摘要需要记录版本和可追溯来源，避免摘要错误永久固化。

## 6. LLM Gateway

### 统一入口价值

若业务代码直接调用模型，会重复处理超时、重试、Prompt、审计、费用和降级。Gateway 把这些横切能力集中：

```text
Prompt Registry + Model Router
    → Primary OpenAI-compatible Endpoint
    → Fallback Endpoint
    → RULE_WORKFLOW
```

### Model Router

- 小模型：意图分类、Query Rewrite、Memory Extraction。
- 中模型：训练分析。
- 强模型：计划生成。

路由按任务复杂度匹配模型，目标是在质量、延迟和成本之间折中；真实选择仍需评测而不是凭感觉。

### Prompt Registry

系统 Prompt 集中管理并带版本号。每次 LLM Invocation 和 Agent Execution 保存 Prompt Version，便于回归定位、成本归因和回滚。Prompt 变更应像代码一样评审、测试和版本化。

## 7. 超时、重试、退避、熔断与降级

### FitPilot 配置

- 连接超时 3 秒，请求超时 15 秒。
- 主备、重试和退避共享默认 20 秒全链路 Deadline，避免单请求累计阻塞约 90 秒。
- Bulkhead 默认限制 16 个并发调用，舱壁获取超时 50ms，饱和时快速失败。
- 429、502、503、504 和 IO 错误最多重试 2 次。
- 指数退避加随机抖动，并识别 `Retry-After`、限制最大等待，减少重试同步风暴。
- Schema、安全、权限和非重试 HTTP 错误不重试。
- 连续失败达到阈值 5，熔断 30 秒；窗口结束只允许一个半开探测，成功才闭合。
- Primary 失败尝试 Fallback；全部失败回退确定性 Rule Workflow。

### 概念区别

- **超时：** 限制单次等待，防线程长期占用。
- **重试：** 处理瞬时错误，但会放大流量和副作用。
- **退避/抖动：** 拉开重试，避免惊群。
- **熔断：** 持续失败时快速失败，让依赖恢复。
- **降级：** 返回能力较弱但安全可用的结果。
- **隔离：** 用 Semaphore Bulkhead 限制 LLM 并发，供应商变慢时不会无限占用请求线程。

### 项目熔断器边界

当前 Circuit 是单 JVM 内存状态，多实例不共享；重启会清空状态。它按 Endpoint 独立计数并支持单探针半开恢复，足以保护单实例，但不是全局供应商健康判断。若需要跨实例统一供应商治理，应在网关侧聚合指标和限流，而不是用 Redis 强行共享每次熔断状态。

### 重试为什么危险

写请求若无幂等会重复副作用；LLM 请求也会重复计费。项目只重试可恢复 Provider 错误，Tool 写入仍由 Pending Confirmation 单独控制，并记录降级与成本。

## 8. 结构化输出与 Prompt Injection

模型输出计划时要求 JSON Object，再反序列化为 DTO。服务端限制最大上下文字符数，检索到的 RAG 内容被当作不可信数据包裹。

Prompt Injection 防御不是“加一句忽略恶意指令”即可：

- 系统指令与外部数据分层。
- Tool 最小权限和参数白名单。
- 身份不从 Prompt 获取。
- 写操作必须确认。
- 输出强类型校验与领域校验。
- Citation 由服务端数据构造。
- 敏感信息脱敏后才进入模型与审计。
- 使用对抗样本持续评测。

## 9. LLM 审计、隐私与成本

每次调用记录 Endpoint、Model、Task、Prompt Version、状态、Token、费用、延迟、HTTP 状态和错误码；输入先经过脱敏，明细默认保留 30 天。

成本公式：

```text
cost = inputTokens × inputPrice / 1,000,000
     + outputTokens × outputPrice / 1,000,000
```

不可在日志或 Span 中记录 API Key、完整用户问题、Tool 响应和高基数用户 ID。审计要在可追溯与隐私最小化之间平衡。

## 10. Agent 离线评测

项目使用版本化中文用例评估：

- Tool Selection Accuracy。
- Task Success Rate。
- Constraint/Rule Violation Rate。
- Tool Hallucination Rate。

CI 用 Mock OpenAI-compatible Server 保证确定性与协议链路，真实模型通过手动/夜间 Workflow 评测。原因是外部模型会变化、费用和网络不稳定，不应让普通 CI 变成随机门禁。

**问：Mock 测试能证明模型效果吗？** 不能。它证明 Gateway、解析、Fallback、审计和 Workflow 集成正确；真实模型质量必须由冻结数据集评测。

## 11. MCP

MCP 是模型/Agent 与 Tool、Resource 服务之间的标准协议层。FitPilot `/mcp` 是薄适配器，支持发现、Tool 列表/调用、Resource 列表/读取；认证仍使用 Bearer JWT，Tool 最终复用同一 Application Service、Owner 与审计链。

写 Tool 返回 `input_required`，仍通过 FitPilot 的确认 API 完成，不因换成 MCP 就绕过业务安全协议。

### 高频追问

**问：MCP 和 Function Calling 有什么区别？** Function Calling 通常是模型 API 内的 Tool 描述/调用格式；MCP 定义客户端与独立 Tool/Resource Server 的发现和交互协议。两者都不自动解决授权和业务安全。

**问：为什么做薄适配器？** 避免协议层复制业务逻辑；无论 REST 还是 MCP，最终都走同一权限、领域校验、事务和审计。

## 12. AI 产品指标

功能完成不等于产生用户价值。项目直接从 PostgreSQL 事实聚合：

- D7 会话留存：首条用户消息后第 1-7 天再次发消息，观察期未满不进分母。
- 建议接受/拒绝率：只用已决建议做分母。
- 确认转化率：接受数 / 有提议的建议数。
- 规则降级率：Rule Workflow 执行 / 全部执行。
- 单次成功成本：窗口总成本 / 成功或待确认执行数。
- 训练调整结果：以接受草案激活日为锚点，对比等长前后窗口的完成率、疼痛、容量和 PR。

指标按 Prompt、Model、Intent 分组，并返回调整 ID 以回查原始证据。Prometheus 告警设置最小样本数，避免小样本比例误报。

### 相关不等于因果

调整前后变好可能来自季节、训练阶段、用户自选择或回归均值。当前指标证明聚合链路和观察性结果，不证明 AI 导致收益。要做因果评估，应引入随机分组、曝光记录、样本量/功效分析、守护指标和固定实验窗口。

## 13. 高频追问

**问：为什么不用 Multi-Agent？** 当前任务可由确定性 Workflow 完成，多 Agent 会增加路由、共享状态、循环、成本和调试难度；只有单 Workflow 无法满足可量化场景时才引入。

**问：Rule Workflow 算不算 Agent？** 它是 Agent 的确定性降级规划器，保留 Tool 选择和 Workflow，但不依赖模型。重点是业务能力在模型不可用时仍安全退化。

**问：模型幻觉如何彻底消除？** 无法彻底消除，只能降低与隔离：检索证据、结构化输出、白名单 Tool、领域校验、确认、拒绝策略、评测和监控。

**问：如何防止模型成本失控？** 任务路由、Context 截断/检索、缓存、并发/预算限制、Token/成本指标、异常告警、低价值请求规则化，以及按成功任务计算单位成本。

**问：为什么确认后创建 DRAFT 而不是修改 ACTIVE？** 保留当前可用计划和回滚路径，用户能对比、拒绝或后续激活；AI 不直接改写正在执行的事实基线。

## 14. 本章一段话总结

> FitPilot 使用显式单 Agent Workflow，而不是让模型自由循环。LLM 只产生结构化提议，身份来自 JWT，Tool 白名单、领域校验、Guardrail、一次性确认和 DRAFT 持久化由后端控制。Gateway 统一 Prompt 版本、模型路由、超时重试、主备切换、熔断、规则降级、审计和成本。MCP 只是复用同一业务边界的协议适配器。产品指标能证明使用和观察性变化，真实收益仍需随机对照。

# LLM 熔断与降级

## 预期链路

每类任务按 Model Router 选择模型，调用顺序固定为主端点、备用端点、`RULE_WORKFLOW`。网络错误、429、502/503/504 最多抖动重试两次；Schema、权限和 Guardrail 错误不重试。

## 处置

1. 通过 LLM Dashboard 和 `/api/v1/operations/llm/status` 确认端点、熔断状态、fallback 比例和错误码。
2. 主端点失败时保留备用端点，检查其限额与成本；fallback 超过 10% 立即告警。
3. 双端点失败时保持 `RULE_WORKFLOW`，不得绕过 Tool 白名单、owner、Guardrail 或一次性确认。
4. 若响应 Schema 异常，回滚 Prompt 版本或模型路由，不扩大网络重试。
5. 恢复后先以小流量验证结构化输出、Token/费用审计和引用真实性，再恢复全部流量。

故障演练命令：

```powershell
./scripts/invoke-failure-drills.ps1 -OperationsToken $env:OPERATIONS_TOKEN -IncludeLlm
```

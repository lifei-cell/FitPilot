# RAG 无答案误引 P0 修复与独立样本验收

日期：2026-09-19。验收基线：`b61c477`（P0 实现 `7f30344`，并强化为 v2 零候选断言）。本报告记录 P0 实现、第二组独立难负例的一次性验收和完整质量门禁；不把本地检索证据扩大为真实模型或生产结论。

## 修复内容

原有 BM25、向量和 RRF 只表达“主题相关性”，不能证明候选段落包含问题所要求的具体事实。现在在检索结果离开 `HybridRetrievalService` 前增加共享的确定性证据门槛：

- [EvidenceSupportGate](../../src/main/java/com/fitpilot/rag/application/EvidenceSupportGate.java) 对问题做 NFKC 归一化和词元抽取，检查查询锚点覆盖；比赛规则、医疗/药物、品牌比较、精确数值等具体请求若未出现在段落中直接拒答。
- 证据门槛默认开启，默认查询锚点覆盖率为 `0.4`，可通过 `RAG_EVIDENCE_GATE_ENABLED` 和 `RAG_EVIDENCE_MIN_QUERY_TERM_COVERAGE` 配置。
- `HybridRetrievalService` 只保存通过门槛的上下文；没有通过时返回空 `contexts`，并在 API 响应中给出 `evidenceDecision=ABSTAINED` 与原因。通过时返回 `SUPPORTED/EVIDENCE_FOUND`。
- `SearchResponse` 保留原有六参数构造兼容性，新增证据状态字段，避免调用方只能根据候选数量猜测拒答原因。

这是一个保守的规则门槛，不是通用自然语言蕴含模型，也不生成答案。

## 新独立样本

第二轮在首轮 cosine 和 Qwen 逐候选实验揭盲后重新冻结 `hard-negatives-v2.json`，本次只作一次留出验收：

- 20 道近主题但语料缺失具体事实的问题，覆盖比赛规则、医疗、器材、强度生理指标、营养剂量和比较请求。
- 文件 SHA-256：`AB48E6BC7B894FE950C0F7BB17CD3B2906E6757F99134249E14FD99FDEFB44DC`。
- 标签由项目评估者对照当前 10 篇合成语料核对，尚无外部领域专家盲审。验收后不再用 v2 调参；后续修改必须重新冻结新留出集。

## 验收结果

`RagAbstentionAcceptanceIT` 使用真实 Testcontainers：`pgvector/pg17`、Redis 7.4 和 Elasticsearch 8.17.4；索引和数据库均通过当前应用链路写入。验收使用 `fitpilot.rag.embedding.provider=LOCAL` 的确定性本地向量，仅验证证据门槛和检索链路，不代表 MiniLM 或线上 Embedding 的效果。

| 检查 | 结果 | 门槛 |
|---|---:|---:|
| v2 无答案题返回潜在引用 | **0/20** | ≤ 1/20 |
| `rag-v1` 可回答题 BM25 Recall@3 | **50/50** | 不低于回归基线 |
| `rag-v1` 可回答题向量 Recall@3 | **50/50** | 不低于回归基线 |
| `rag-v1` 可回答题混合 Recall@3 | **50/50** | 不低于回归基线 |
| 证据门槛单测 | **3/3** | 失败 0、跳过 0 |

同时，`FitPilotRagFlowIT` 验证了支持查询返回 `SUPPORTED/EVIDENCE_FOUND`，删除知识后查询返回 `ABSTAINED`，确保拒答状态从服务层传到 API。

## 完整门禁

执行环境为 Windows + Docker Desktop，通过 `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` 执行：

```text
mvn clean verify -DskipTests=false -DskipITs=false -Dmaven.test.skip=false
单元测试：74；集成测试：47；失败：0；错误：0；跳过：0
JaCoCo coverage-gate：通过
zero-skipped-tests-gate：通过（tests=121 skipped=0）
```

## 结论与边界

P0 已合入：当前检索链路在新的 v2 独立样本上不再把无答案问题的候选上下文暴露为引用，且 50 道可回答回归题的三路 Recall@3 保持 50/50。该结论限于本地确定性证据门槛、当前 10 篇合成语料和 Testcontainers 环境；尚未证明真实 MiniLM/商业模型、开放域语义蕴含、人工最终答案引用支撑或生产误引率。后续扩充语料或调整规则时，应重新冻结独立难负例并单独验收。

首轮失败的 cosine 与 Qwen 判定实验仍保留在[历史报告](rag-abstention-2026-09-18.md)，不能与本次通过结果混写。

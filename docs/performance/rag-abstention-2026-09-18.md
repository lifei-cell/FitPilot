# RAG 无答案拒答与证据门槛验证

## 实验边界

基于 `bea7c4b` 的 10 篇合成健身语料、24 道可回答题、原有 4 道无答案题和冻结的 20 道近主题难负例。三路排序分别为 BM25、MiniLM 向量和等权 RRF。指标中的“误引”指在无答案题仍返回带引用候选，是生成层之前的潜在误引，并非人工审核过的最终回答错误率。

## 首轮门槛失败

首轮采用候选子块与问题的精确 cosine，相似度阈值 0.30；`searchOfflineWithEvidence` 在引用出站前过滤。原 4 道无答案题从三路各 4/4 返回候选变为 0/4；24 道可回答题三路 Recall@3 均为 24/24，首位证据支撑率分别为 BM25 20/24、向量 16/24、混合 20/24，均不低于原基线。

**难负例不通过：三路均有 17/20 返回候选。** 问题与文段同属训练主题时，cosine 衡量相关性而非“是否包含所问的精确事实”。这 20 道题已在首轮运行中揭盲，后续只能作开发集；最终验收必须重新冻结独立难负例。

证据：[`raw.json`](evidence/rag-abstention-20260918-first/raw.json)、[`summary.json`](evidence/rag-abstention-20260918-first/summary.json)、[`model-stats.json`](evidence/rag-abstention-20260918-first/model-stats.json)、[`manifest.json`](evidence/rag-abstention-20260918-first/manifest.json)。首轮 BM25 也需一次查询 Embedding 和 pgvector 精确相似度查询，不能按原纯 BM25 成本报告。

## 第二轮独立留出验收失败

在首轮揭盲后，冻结另外 20 道近主题问题，哈希见[实验协议](rag-abstention-protocol-2026-09-18.md)。试验实现改用本地 Qwen2.5-1.5B-Instruct 逐候选判断 `SUPPORTED/ABSENT`，最多核验排序前 3 篇 Parent Context，异常按检索不可用处理。排序与 Embedding 仍使用真实 PostgreSQL、Elasticsearch、MiniLM ONNX；此轮每题每路只测一次，且证据模型缓存了重复的问题与文段，因此跨路径 P95 不具备公平的冷调用可比性。

| 路径 | 原 4 道误放 | 新 20 道误放 | 可回答 Recall@3 | 首位支撑 | 观测 P95 |
|---|---:|---:|---:|---:|---:|
| BM25 | 0/4 | 9/20 | 22/24 | 20/24 | 12.64 s |
| 向量 | 0/4 | 10/20 | 23/24 | 20/24 | 6.56 s |
| 混合 | 0/4 | 10/20 | 23/24 | 21/24 | 3.21 s |

三路均超过“新难负例最多 1/20 误放”的上限；BM25 的 Recall@3 从原 23/24 降到 22/24，向量与混合从 24/24 降到 23/24。`term-05` 在三路都被误拒，`paraphrase-07` 还在 BM25 被误拒。新负例中的比赛规则、医疗因果和精确剂量等请求仍会被同主题文段误判为有证据。判定模型在旧开发集上曾达到 24/24 可回答与 1/20 难负例误放，但该结果不能替代新留出验收。

该运行发生 430 次判定请求，其中 220 次命中缓存；实际推理 690.35 s，输入 260,251 tokens、输出 1,290 tokens，模型进程工作集约 6.97 GB。模型本地运行，没有外部 API 费用；这里的成本是本机 CPU、内存和延迟，不等于云端生产成本。记录：[`raw.json`](evidence/rag-abstention-20260918-model-v2/raw.json)、[`summary.json`](evidence/rag-abstention-20260918-model-v2/summary.json)、[`evidence-model-stats.json`](evidence/rag-abstention-20260918-model-v2/evidence-model-stats.json)、[`manifest.json`](evidence/rag-abstention-20260918-model-v2/manifest.json)。

实验代码可由[`补丁`](evidence/rag-abstention-20260918-model-v2/experiment-tracked.patch)和[`新增文件快照`](evidence/rag-abstention-20260918-model-v2/prototype/)复核。验收任务本身执行成功，`manifest.status=PASS` 只表示脚本跑完；上表的质量门槛判定为**失败**。

## 交付决定

**不合入本次拒答实现。** 单独的 cosine 和当前小模型判断均未通过独立难负例与召回双门槛；将其默认启用会产生误引与误拒。保留冻结集、逐题证据和失败原因，后续需使用更可靠的问答支撑/蕴含判定，并在新的独立标注集重新验收。新集需要包含否定答案、同主题缺失事实和多个近似候选；还应单独量化冷调用 P95、缓存命中及模型资源。当前项目不能宣称已经完成无答案拒答。

回退实验代码后，完整 `mvn clean verify` 在本地 Docker 环境通过：单元测试 71、集成测试 45，失败/错误/跳过均为 0；原 RAG、Agent、事件和并发集成测试均通过。日志见[`quality-gate.txt`](evidence/rag-abstention-20260918-model-v2/quality-gate.txt)。用户原有简历文件修改未纳入本次提交。

# 真实 Embedding 检索对照协议（预先冻结）

## 范围与版本

- 同一份 `eval/rag-corpus-v1.jsonl`，10 篇项目内合成训练知识，统一索引到 PostgreSQL/pgvector 与 Elasticsearch。固定当前默认分块、候选数和 `RRF k=60`。
- 独立留出集 `src/test/resources/retrieval/holdout-v1.json`：28 问，术语、改写、场景各 8 问，无答案 4 问；2026-09-17 首次运行前 SHA-256 为 `EDDC5922F71DF776E8474BB7EB01AF3762CF627CCCEADAC1EA15546BB527DE91`。它与原有 `comparison/rag-queries.json` 分离，后者不得用于本次调参。标签由项目评估者根据原文写出，尚未经过独立领域专家盲审。
- Embedding 为 `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` 的 384 维量化 ONNX CPU 推理。固定模型 revision `e8f8c211226b894fcb81acc59f3b34ba3efd5f42`、文件 `onnx/model_quint8_avx2.onnx` SHA-256 `98A01D88B7DE996CDEA58C32CA71208C09968D143798814B2EA09D3439DC334F`。本地 HTTP 仅监听 loopback。
- 路径：BM25 单路、pgvector 单路、BM25 + pgvector 等权 RRF。禁用 rerank；预热每路径每问一次，随后轮换路径顺序做 3 轮。测量同一服务方法端到端延迟，不计索引构建；单独记录索引时间。

## 指标与预设决策

- 可回答问题：分层及总体 Hit/Recall@1、@3、@5（每问一个相关来源），MRR；前 1 条引用支撑率。支撑须同时满足来源 URL 等于标签且返回的父片段包含预标注的逐字证据。它是**检索上下文支撑代理指标**，不是人工核对生成答案是否由引用支持。
- 无答案问题：统计仍返回候选上下文的比例和前 1 条误引率。当前检索器无拒答阈值，预计这些数值可能较差；不因算法排名较好而掩盖。
- 性能：每路径 84 次计时的 P50/P95；本地模型请求数、token 数、推理 CPU 墙钟时间、常驻内存与模型大小。未测云 API 账单；本地运行的直接模型 API 费用为 0，不代表基础设施免费。
- 仅当混合方案在**改写和场景两层**的 MRR 均比两个单路方案至少高 `0.05`，两层 Recall@5、前 1 条证据支撑率均不下降，三轮指标一致，且 P95 小于 BM25 的 2 倍时，才把本语料上的混合检索记为有稳定收益。28 问和 10 篇合成文档仍不足以推断生产或用户收益；若不达标，结论为“必要性证据不足”，保留后续扩充真实授权语料、独立人工盲审和成本复测任务。

## 证据

准备 Python 3、`numpy==2.4.3`、`onnxruntime==1.24.4`、`tokenizers==0.22.2`、`huggingface-hub==1.7.2`，运行 `python scripts/evaluation/prepare_model.py` 拉取公开模型。网络若使用 SOCKS 代理，需在本机 Python 环境安装 `socksio`。此步骤只下载公开模型，不上传项目语料。模型缓存在被 Git 忽略的 `target/models/`；运行脚本重新核对模型与留出集 SHA。

运行脚本 `scripts/evaluation/run-real-retrieval.ps1` 生成 `target/real-retrieval/<RunId>/`，保存 manifest、原始逐问结果、模型服务统计、Maven 日志与容器镜像信息。报告引用固定 RunId 与 SHA，失败运行不得混入结果。

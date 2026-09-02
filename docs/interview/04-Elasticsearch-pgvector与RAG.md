# Elasticsearch、pgvector 与 Hybrid RAG

## 1. 为什么需要 Hybrid RAG

健身知识同时包含精确术语和语义表达：

- BM25 擅长“RPE、RIR、深蹲膝内扣”等关键词，但对同义改写较弱。
- 向量检索擅长语义相似，但可能把术语不精确、看似相关的内容排前。
- 只返回小 Chunk 容易缺上下文；只返回整篇文档又浪费 Context Window。

FitPilot 使用 BM25 + pgvector 双路召回，RRF 融合后确定性重排，命中 Child 但返回 Parent Context，并附带真实 Citation。

## 2. Elasticsearch 倒排索引

### 原理

倒排索引从“文档有哪些词”转换为“每个词出现在哪些文档”：

```text
RPE → doc1, doc8
深蹲 → doc2, doc5
```

文本先经过 Analyzer：字符过滤、Tokenizer、Token Filter。索引时与查询时 Analyzer 不一致会导致召回异常。中文需要分词或合理的字符/Bigram 策略。

### BM25

BM25 的核心考虑：

- TF：词在文档中出现越多通常越相关，但收益逐渐饱和。
- IDF：越稀有的词区分度越高。
- 文档长度归一化：避免长文档仅因词多而占优。

常见公式可概括为：

```text
score(q,d) = Σ IDF(qi) × TF_saturation(qi,d) × length_norm(d)
```

参数 `k1` 控制词频饱和速度，`b` 控制长度归一化程度。面试不必死背完整公式，但要能解释三个因素。

### FitPilot 落地

Elasticsearch 索引 Child Chunk 的 `lexicalText/title/heading`，检索后返回 Chunk ID 和排名。PostgreSQL 才是文档、版本、Chunk 和 Embedding 真源，ES 索引可以删除重建。

索引使用 Bulk API，写后 `refresh=wait_for`；删除通过 Delete By Query。ES 不可用时检索降级为 `VECTOR_ONLY`。

### Refresh、Flush、Merge

- Refresh：生成可搜索 Segment，近实时可见，不等于持久化到安全点。
- Flush：提交 Lucene 状态并处理 Translog 世代。
- Merge：合并不可变 Segment、清理删除标记，可能消耗 IO/CPU。

**追问：为什么 ES 是近实时？** 新文档写入后要等 Refresh 产生可搜索 Segment，默认不是每次写都立刻对查询可见。

## 3. ES 分片、副本与一致性

- Primary Shard 决定数据分布，Replica 提供容错和读能力。
- 分片太少限制扩展，太多增加 Heap、文件句柄、Merge 和集群状态成本。
- 文档按 Routing Hash 定位 Primary；自定义 Routing 可让同聚合共置，但会产生热点风险。
- 写成功条件由等待 Active Shard 数等配置影响；副本同步和 Refresh 可见性是不同问题。

FitPilot 当前单机 Compose 用于功能验证，不应把单节点结果外推到生产 ES 集群。生产要根据数据量、QPS、更新频率、保留期和节点规格设计分片。

## 4. Embedding 与向量相似度

Embedding 把文本映射到高维向量，使语义相近文本在向量空间距离更近。FitPilot 固定 384 维：本地测试使用确定性 Hashing Embedding，生产可切 OpenAI-compatible 服务。

### 常见距离

- Cosine Similarity：比较方向，常用于文本向量。
- Dot Product：受方向和模长影响，若向量归一化后与 Cosine 排序等价。
- Euclidean/L2：比较直线距离。

项目使用 pgvector `vector_cosine_ops`。查询模型、文档模型、维度和归一化方式必须一致，不能直接混用不同模型生成的向量。

## 5. pgvector 与 HNSW

### 精确检索与 ANN

精确 KNN 扫描所有向量，结果准确但规模大时昂贵。ANN 用少量准确率换低延迟。HNSW 构建多层近邻图：高层做长距离跳转，底层细化搜索。

重要参数概念：

- `m`：每个节点连接数，越大通常召回更好但索引更大、构建更慢。
- `ef_construction`：建图搜索宽度，影响构建成本和质量。
- `ef_search`：查询搜索宽度，越大召回更高但延迟更高。

FitPilot 对 `knowledge_chunk.embedding vector(384)` 建 HNSW Cosine 部分索引，只索引 `chunk_type='CHILD'`，减少索引体积。

### 为什么 PostgreSQL 做向量真源

- 文档生命周期、版本、来源、Chunk 和向量可在同一事务中管理。
- 查询时可用 SQL 硬过滤 ACTIVE、有效期和类别。
- 备份、审计和迁移路径统一。

代价是向量规模极大或独立扩展需求出现时，数据库计算和存储压力可能成为瓶颈；届时可评估独立向量数据库，但要解决数据同步和过滤一致性。

## 6. Parent-Child Chunk

### 为什么切块

整篇文档向量会稀释局部语义，过小 Chunk 又缺上下文。FitPilot：

- Parent 上限 2400 字符，保留标题和完整段落语境。
- Child 上限 700 字符，重叠 100 字符，用于 BM25/向量检索。
- 命中 Child 后回填 Parent，并按 Parent 去重。

### Chunk 策略取舍

- Chunk 太大：召回不精确、Token 成本高。
- Chunk 太小：语义断裂、答案缺依据、重复结果多。
- Overlap 太大：索引膨胀和重复召回。
- 固定字符切分简单但可能切断表格/代码；更成熟方案可按 Markdown 标题、句子、语义边界和文档类型切分。

面试时应说“Chunk 参数需要通过离线评测选择”，而不是声称 700/100 对所有知识最优。

## 7. Reciprocal Rank Fusion

BM25 和向量分数尺度不同，直接加权需要归一化并容易受模型变化影响。RRF 只使用排名：

```text
score(d) = Σ 1 / (k + rank_i(d))
```

项目默认 `k=60`。同一文档在多路排名靠前会得到更高分。RRF 稳健、无需校准原始分数；缺点是忽略分数差距，无法表达第一名远强于第二名。

融合后项目再根据词项覆盖、完整短语和可信等级做有限确定性重排。可信等级只小幅加分，不能让低相关官方文档压过高相关内容。

## 8. 检索降级

```text
BM25 成功 + Vector 成功 → HYBRID
BM25 失败 + Vector 成功 → VECTOR_ONLY
BM25 成功 + Vector 失败 → BM25_ONLY
两路都失败                → 503
```

降级模式必须进入响应、指标和日志，否则表面 200 会掩盖检索质量退化。生产还应关注各模式比例、P95、空结果率和 Citation 覆盖率。

## 9. 文档摄取与索引一致性

摄取流程：解析 → Parent/Child → Embedding → PostgreSQL 事务保存 → ES 索引。

ES 不参与 PostgreSQL 本地事务，因此同步索引失败时文档标记 `FAILED/PENDING`，后台任务按批次重试。删除先在 PostgreSQL 标记 `DELETE_PENDING` 并移除 Chunk，使其立即无法被向量查询；ES 删除由持久化任务重试，完成后正文清空但保留审计字段。

这仍是最终一致，不宣称数据库和 ES 原子同步。正确性由“查询双侧过滤 + 可重试任务 + 状态监控”保证。

## 10. Citation 与内容治理

每个检索结果返回：文档 ID、来源 URL、许可证、发布方、可信等级、版本、有效期。每次检索保存 `retrievalId` 和结果快照，用于后续反馈审计。

生命周期：`ACTIVE / EXPIRED / REVOKED / DELETE_PENDING / DELETED`。PostgreSQL 和 ES 都过滤不可检索状态及过期文档。

用户可对回答或单 Citation 提交反馈，但反馈不会直接调整在线权重；Operations 人工审核并提供正确来源后，才进入版本化动态评测集。这避免恶意反馈或偶然负反馈立即污染线上排序。

## 11. RAG 评测

### 指标

- Recall@5：期望相关来源是否至少有一个出现在前 5。
- MRR：第一个相关结果排名的倒数，越靠前越好。
- NDCG：考虑多个相关结果与排名折损。
- Context Precision：返回上下文中相关项比例。
- Context Recall：期望来源被覆盖的比例。
- Citation Validity：引用元数据是否完整、有效且未过期。

项目冻结静态数据集和审核后的动态样本版本，输出总体及分类指标；Citation Validity 必须 100%，任一分类 Recall@5/MRR 比最近成功基线下降超过 5 个百分点即失败。

### 为什么不能只看“回答质量”

生成答案会混合检索、Prompt 和模型能力。先独立评测 Retrieval，才能定位是“没召回”“排序错”“上下文坏”还是“模型没使用证据”。线上还要看点击、反馈、引用打开率和任务成功，但这些是观察性信号。

## 12. Prompt Injection 与 RAG

知识文档是外部不可信数据。项目将检索上下文包装为数据，不允许其中内容覆盖系统指令或触发 Tool；Citation 由服务端检索结果生成，模型不能自由编造。

进一步防御：内容来源准入、指令模式检测、结构化上下文、Tool 权限最小化、输出引用校验、敏感信息过滤、红队数据集和人工审核。

## 13. 高频追问

**问：为什么不只用 Elasticsearch 向量检索？** 项目需要 PostgreSQL 事务化保存文档版本、生命周期和向量真源，同时保留 ES 擅长的 BM25；双系统各承担优势，但接受最终一致性成本。

**问：RRF 为什么比加权分数稳？** 它绕过 BM25 与 Cosine 分数尺度差异，只融合相对排名；模型或索引变更时无需重新校准绝对分数。

**问：HNSW 一定比 IVFFlat 好吗？** 不一定。HNSW 通常查询召回和延迟好，但构建慢、内存/磁盘更高；IVF 训练和分桶适合部分规模。应按数据量、更新频率、召回目标和资源测试。

**问：如何处理 Embedding 模型升级？** 新建版本字段与新向量列/表或双索引，离线回填，使用同一评测集对比，灰度切流，确认后再清理旧向量；不能混用新旧向量直接比较。

**问：删除为什么先删 PostgreSQL Chunk？** PostgreSQL 是向量真源；先使主查询立即不可见，再异步传播 ES 删除。若先删 ES 而 PG 仍可查，VECTOR_ONLY 降级会返回已删除内容。

## 14. 本章一段话总结

> FitPilot 的 Hybrid RAG 让 Elasticsearch 负责可重建 BM25，PostgreSQL/pgvector 负责文档、版本、生命周期和向量真源。Parent-Child Chunk 兼顾召回粒度与回答上下文，BM25 与 HNSW 候选通过 RRF 融合并确定性重排。双系统采用状态机和后台任务达到最终一致，查询双侧过滤失效内容；反馈必须人工审核后进入冻结评测集，Citation 和分类回归门禁约束质量。

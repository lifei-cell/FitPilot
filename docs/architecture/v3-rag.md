# FitPilot V3 Hybrid RAG 架构

## 目标与边界

V3 提供独立于 LLM 的专业健身知识库能力：合法来源文档摄取、Parent-Child Chunk、Embedding、BM25、向量检索、RRF、Rerank 和带引用的上下文构建。生成式回答、Agent Tool Calling 与会话 Memory 属于 V4。

知识库只接收运维侧确认可使用的内容。摄取请求必须提供 `sourceUrl` 与 `sourceLicense`，系统在每条检索结果中原样返回引用；许可证真实性仍需内容运营方审核。

## 数据与索引架构

```text
                       ┌────────────────────────────┐
Markdown / Text ──────→│ Parser + Parent/Child Split│
                       └─────────────┬──────────────┘
                                     │
                          ┌──────────▼───────────┐
                          │ Embedding (384 dims) │
                          └──────────┬───────────┘
                                     │
                ┌────────────────────┴────────────────────┐
                ▼                                         ▼
 PostgreSQL + pgvector                           Elasticsearch
 文档真源、Parent/Child、HNSW                    Child BM25 可重建索引
```

`knowledge_document` 保存原文、来源、许可证、内容哈希、版本及 `PENDING/INDEXING/INDEXED/FAILED` 状态。`knowledge_chunk` 保存 Parent Context 和可检索 Child；只给 Child 生成向量并建立 HNSW cosine 索引。

文档写入和 Chunk/Embedding 落库在同一 PostgreSQL 事务中完成。Elasticsearch 不是真源：同步索引失败不会丢失文档，后台任务会重试 `PENDING/FAILED` 文档，人工也可触发重建。

## 检索与排序

1. 查询经过 NFKC、小写化、英文词元和中文单字/双字词元归一化。
2. Elasticsearch 对 `lexicalText/title/heading` 执行 BM25。
3. 查询 Embedding 在 pgvector HNSW 中执行 cosine KNN。
4. 两路候选使用 Reciprocal Rank Fusion：`score = Σ 1 / (rrfK + rank)`，默认 `rrfK=60`。
5. 确定性 Reranker 基于 RRF、查询词覆盖率和完整短语命中重新排序。
6. 检索命中 Child，但返回对应 Parent Context，按 Parent 去重，并附带来源与许可证。

Elasticsearch 不可用时返回 `VECTOR_ONLY`；Embedding/pgvector 不可用时返回 `BM25_ONLY`；两路均失败才返回 503。生产环境可把默认本地确定性 Embedding 切换为 OpenAI-compatible 384 维服务。

## API

知识检索需要用户 JWT：

```http
GET /api/v1/rag/search?q=RPE%208%20代表什么&topK=5&category=training-theory
Authorization: Bearer <jwt>
```

知识库写操作使用常量时间比较的 `X-Operations-Token`：

```http
POST   /api/v1/operations/rag/documents
GET    /api/v1/operations/rag/documents?limit=50
POST   /api/v1/operations/rag/documents/{id}/reindex
DELETE /api/v1/operations/rag/documents/{id}
```

摄取示例：

```json
{
  "externalId": "fitness:rpe-guide",
  "title": "RPE 与 RIR 指南",
  "category": "training-theory",
  "sourceUrl": "https://knowledge.example/rpe",
  "sourceLicense": "CC-BY-4.0",
  "format": "MARKDOWN",
  "content": "# RPE\nRPE 8 通常对应 RIR 2。",
  "metadata": {"language": "zh-CN"}
}
```

相同 `externalId` 再次摄取会原子替换 PostgreSQL Chunk、递增版本并重建 Elasticsearch 文档，不会产生重复知识。

## 关键配置

| 环境变量 | 默认值 | 说明 |
|---|---:|---|
| `RAG_ENABLED` | `true` | 启用 V3 模块 |
| `RAG_OPERATIONS_TOKEN` | 事件运维 Token | 知识库写操作密钥 |
| `RAG_ELASTICSEARCH_URL` | `http://localhost:9200` | BM25 服务 |
| `EMBEDDING_PROVIDER` | `LOCAL` | `LOCAL` 或 `OPENAI_COMPATIBLE` |
| `EMBEDDING_DIMENSIONS` | `384` | V3 固定维度 |
| `RAG_PARENT_MAX_CHARS` | `2400` | Parent 上限 |
| `RAG_CHILD_MAX_CHARS` | `700` | Child 上限 |
| `RAG_CHILD_OVERLAP_CHARS` | `100` | Child 重叠窗口 |
| `RAG_CANDIDATE_LIMIT` | `30` | 每路召回候选数 |
| `RAG_RRF_K` | `60` | RRF 平滑常量 |

## 验收

`FitPilotRagFlowIT` 使用真实 pgvector 与 Elasticsearch，验证文档摄取、Parent-Child 数量、BM25 + Vector 双路命中、RRF 模式、Parent Context、许可证引用、权限、重建索引与删除。切分和本地 Embedding 另有独立单元测试。

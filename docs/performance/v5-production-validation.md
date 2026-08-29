# FitPilot V5 生产验证报告

验证日期：2026-08-29。以下结果只记录本机实际执行证据，不把静态配置或未完成场景写成通过。

## 已通过

- `mvn verify`：18 个单元测试、11 个 Testcontainers E2E，失败 0、跳过 0；JaCoCo 整体行覆盖率 79.14%，整体 60% 和关键包 70% 门禁均通过。
- RULE_WORKFLOW Agent 数据集：150/150，工具选择正确率 100%、任务成功率 100%、规则违规率 0、Tool 幻觉率 0。
- RAG `rag-v1.1` 数据集：50/50，Recall@5、MRR、NDCG、Context Recall 和引用有效率均为 1.0；Context Precision 为 0.2。
- 1 分钟混合流量预检，目标 20 iteration/s：1,201 iterations、1,595 HTTP 请求、HTTP 失败 0、业务成功率 100%，普通 API P95 17.95ms，Agent P95 55.41ms。
- 故障演练：Redis 缓存回源、Kafka 中断后 Outbox 补发、Elasticsearch 中断后 `VECTOR_ONLY`、主 LLM 中断切备用、双 LLM 中断切 `RULE_WORKFLOW` 均通过，依赖已恢复。
- Compose 配置、两个 k6 脚本的无流量解析、Kustomize production/migration 渲染和非 root Docker 镜像构建通过。

## 未通过或未执行

- 30 分钟、50 iteration/s 混合流量运行到约 17 分钟时出现超过 100,000 条动态 URL 时序及 3 次 I/O timeout，结果已判定无效并停止，未生成可用于验收的完整报告。
- 已为动态资源 URL 增加低基数 `name` 标签，并完成 k6 静态解析；按用户要求未重跑，因此普通 API P95、Agent P95 和错误率的 30 分钟正式门禁仍未确认。
- 五分钟突发场景未执行。
- 完整 observability profile 未做本次本机在线联调；Dashboard、告警和 Compose 配置仅完成静态验证。
- GitHub Actions、GHCR 推送、安全扫描、真实 Kubernetes rollout/rollback 需要远端环境，本机未执行。

因此 V5 的功能、E2E、离线评测、故障恢复和部署清单已具备证据，但长稳压测、突发压测和真实发布环境门禁仍是发布前待办。

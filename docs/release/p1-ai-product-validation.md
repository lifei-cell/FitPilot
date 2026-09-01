# P1 AI 产品价值验收报告

## 交付范围

| 迁移 | 能力 | 回滚边界 |
|---|---|---|
| V12 | Agent PostgreSQL 会话真源、Redis 热缓存、跨设备历史 | 后端保留旧最近消息 API，新表向前兼容 |
| V13 | Workout 反馈、确定性调整证据、Pending Action 确认新 DRAFT | 拒绝或失效不改计划，ACTIVE 始终不被覆盖 |
| V14 | RAG 来源治理、版本链、删除传播、反馈审核、动态评测 | PG 先硬过滤，ES 任务可重试；历史 Revision 不变 |

部署顺序固定为数据库迁移、后端、Web。V14 将已有知识文档设置为 `COMMUNITY` 并触发重新索引。

## 已执行验证

- 后端：在 Docker Desktop 29.7.2 上执行 `mvn verify`，22 个报告、46 个测试、零失败、零错误、零跳过；Testcontainers 实际启动 PostgreSQL/pgvector、Redis、Kafka 与 Elasticsearch，Flyway V1-V14 全部应用成功，JaCoCo 门禁通过。
- Web：ESLint、TypeScript、14 个 Vitest/RTL 测试和生产构建通过；新增 Workout 反馈、调整卡片、Citation 反馈模块各项覆盖率均为 100%。
- Playwright Chromium 3 个场景通过，包括两个浏览器上下文恢复同一会话、Citation 错误反馈、Workout 反馈和计划编辑。
- `docker compose config --quiet` 与 `docker compose up -d --build` 通过；PostgreSQL、Redis、Kafka、Elasticsearch、后端和 Web 六个核心容器均为 `healthy`。
- 真实 HTTP 冒烟通过：注册/登录、创建 Agent 会话、发送消息、历史分页、会话列表与 RAG 检索均成功；PostgreSQL 验证 `flyway=14`、会话消息为 2 条、检索记录为 1 条，Redis 热缓存命中，Kafka 业务主题存在。
- Actuator 返回 `UP`，Web `/healthz` 返回 `ok`；Elasticsearch 单节点集群为 `yellow`，原因是副本分片在单节点环境无法分配，主分片正常可用。
- 冒烟测试账号、检索记录和 Redis 会话缓存已精确清理；Compose 栈保留运行，便于继续联调。
- 集成验收用例覆盖跨用户隔离、调整确认只建 DRAFT、RAG 版本恢复、反馈审核、动态评测和删除最终传播。

## 发布门禁

RAG 评测要求 Citation Validity 为 100%；任一分类 Recall@5 或 MRR 相对最近成功基线下降超过 5 个百分点即失败。
本地 Docker Compose 全链路门禁已关闭；受保护生产环境验证仍需独立完成，不能由本地 Compose、单元测试或 Kind 演练替代。

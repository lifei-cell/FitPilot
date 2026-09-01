# P1 AI 产品价值验收报告

## 交付范围

| 迁移 | 能力 | 回滚边界 |
|---|---|---|
| V12 | Agent PostgreSQL 会话真源、Redis 热缓存、跨设备历史 | 后端保留旧最近消息 API，新表向前兼容 |
| V13 | Workout 反馈、确定性调整证据、Pending Action 确认新 DRAFT | 拒绝或失效不改计划，ACTIVE 始终不被覆盖 |
| V14 | RAG 来源治理、版本链、删除传播、反馈审核、动态评测 | PG 先硬过滤，ES 任务可重试；历史 Revision 不变 |

部署顺序固定为数据库迁移、后端、Web。V14 将已有知识文档设置为 `COMMUNITY` 并触发重新索引。

## 已执行验证

- 后端：`mvn test` 通过，18 个报告、32 个测试、零失败、零错误、零跳过；关键规则、Redis 降级、会话顺序、计划调整与反馈授权均有单元测试。
- Web：ESLint、TypeScript、14 个 Vitest/RTL 测试和生产构建通过；新增 Workout 反馈、调整卡片、Citation 反馈模块各项覆盖率均为 100%。
- Playwright Chromium 3 个场景通过，包括两个浏览器上下文恢复同一会话、Citation 错误反馈、Workout 反馈和计划编辑。
- `docker compose config --quiet` 通过。
- 集成验收用例覆盖跨用户隔离、调整确认只建 DRAFT、RAG 版本恢复、反馈审核、动态评测和删除最终传播。
- `mvn verify` 已执行，但 Docker named pipe 返回 `AccessDeniedException`，Testcontainers 未运行，JaCoCo 因缺少集成测试覆盖而未过；Compose 真实启动同样未执行。该项是环境阻塞，不能写成生产或容器验收通过。

## 发布门禁

RAG 评测要求 Citation Validity 为 100%；任一分类 Recall@5 或 MRR 相对最近成功基线下降超过 5 个百分点即失败。
生产发布仍需补齐真实 Docker Compose 全链路和受保护生产环境验证，二者不能由单元测试或 Kind 演练替代。

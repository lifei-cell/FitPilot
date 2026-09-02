# AI 产品价值指标验收报告

验证日期：2026-09-02。功能 revision：`06bc3484ba3d9de61c044dc79b0e8ab80d078963`。

## 验收结果

- 在 Windows + Docker Desktop 29.7.2 执行 `./scripts/run-quality-gate.ps1 -SkipInstall -SkipBrowserInstall`，最终输出 `QUALITY_GATE=PASS`。
- 后端共 25 个 Surefire/Failsafe 报告、50 个测试，失败 0、错误 0、跳过 0；Testcontainers 实际启动 PostgreSQL/pgvector、Redis、Kafka 和 Elasticsearch，Flyway V1-V15、JaCoCo 与零跳过门禁通过。
- 后端 JaCoCo 行覆盖率为 81.57%（2916/3575）。
- Web 通过 ESLint、TypeScript、36 个 Vitest/RTL 测试、生产构建和 7 个 Playwright Chromium 场景；行覆盖率 71.69%，语句 69.61%，分支 66.17%，函数 60.06%。
- Prometheus 官方 `promtool` 校验 `deploy/monitoring/prometheus/alerts.yml`，13 条规则全部通过。

## 指标闭环

受保护接口 `GET /api/v1/operations/agent/product-metrics` 已覆盖 D7 会话留存、建议接受/拒绝、确认转化、规则降级、单次成功成本，以及训练调整前后完成率、疼痛、容量和 PR。集成测试使用可控 PostgreSQL 事实验证：

| 指标 | 可控样本结果 |
|---|---:|
| D7 留存 | 1/2，50% |
| 建议接受 / 拒绝 / 确认转化 | 50% / 50% / 50% |
| 规则降级 | 1/4，25% |
| 单次成功成本 | 0.02 USD |
| 完成率变化 | +50 个百分点 |
| 平均疼痛变化 | -3 分 |
| 训练容量变化 | +50% |
| PR 变化 | +1 |

测试同时验证 Operations Token 拒绝非法访问、Prometheus Gauge 可采集、结果样本可回溯至调整 ID。Dashboard 与告警对转化、成本、降级、疼痛和完成率设置了最小样本边界。

## 证据边界

以上受控样本证明的是指标定义、数据库聚合、API、监控和告警链路正确，不代表真实用户已获得同等提升。真实业务价值仍需积累足量线上窗口，并通过小流量随机对照排除训练阶段、季节和用户自选择等混杂因素。本报告也不提供远端 CI、GHCR 或真实生产集群证据。

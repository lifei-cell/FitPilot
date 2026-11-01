# 脚本导航

以下命令从仓库根目录使用 PowerShell 7 执行。脚本已按用途归类；原来平铺在 `scripts/` 下的任务脚本请使用新路径。

| 用途 | 入口 | 前置条件 |
|---|---|---|
| 完整质量门禁 | `./scripts/run-quality-gate.ps1` | Java 21、Maven、Node、Docker / Testcontainers |
| 仅 Web 门禁 | `./scripts/run-quality-gate.ps1 -WebOnly` | Node、Playwright Chromium |
| 演示数据 | `./scripts/development/seed-demo-data.ps1` | `.env` 与运行中的本地 Compose；写入演示账号数据 |
| 在线评测 | `./scripts/evaluation/run-evaluations.ps1 -OperationsToken <token>` | API 与运维 Token |
| 真实模型门禁 | `./scripts/evaluation/run-real-model-quality.ps1` | 配置真实模型、运行中的 API |
| 真实向量检索 | `./scripts/evaluation/run-real-retrieval.ps1` | Python、固定模型文件、Docker |
| 架构对比 | `./scripts/performance/run-architecture-comparison.ps1` | Java、Maven、Docker |
| Outbox 积压 | `./scripts/performance/run-outbox-backlog.ps1` | Java、Maven、Docker |
| 训练写入压测 | `./scripts/performance/run-workout-write-load.ps1` | Java、Maven、Docker |
| 双实例事件恢复 | `./scripts/operations/run-dual-instance-event-recovery.ps1` | `.env`、Docker；创建独立 Compose 项目 |
| 服务故障演练 | `./scripts/operations/invoke-failure-drills.ps1 -OperationsToken <token>` | 隔离测试环境；会停止、恢复服务 |
| 生产验证 | `./scripts/production-validation/run-production-validation.ps1` | Docker、Maven；会启动服务、压测及生成报告 |
| 发布门禁与演练 | `delivery/` | 参阅[发布回滚手册](../docs/runbooks/release-rollback.md) |
| HTTP 压测 | `../load-test/` | 参阅[k6 说明](../load-test/README.md) |

`evaluation/` 同时包含模型准备、Embedding 服务和汇总 Python 工具。`production-validation/` 内的采集与报告脚本服务于同目录主入口。脚本参数见各文件的 `param` 声明。

运行输出保持原有路径：`target/`、`evaluation-reports/`、`release-evidence/`、`load-test/reports/`。这些目录不纳入 Git。正式实验数据需脱敏后归档，不能覆盖既有证据。

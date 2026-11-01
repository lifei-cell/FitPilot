# 文档导航

从[项目总览](../README.md)了解启动方式和核心能力，从[项目结构](project-structure.md)了解文件职责。

| 目录 | 内容 | 阅读入口 |
|---|---|---|
| `architecture/` | 分阶段架构与机制设计 | [V6 AI 产品](architecture/v6-ai-product.md)、[事件机制](architecture/v2-events.md) |
| `runbooks/` | 运维、部署、故障与恢复操作 | [首次部署](runbooks/first-public-deploy.md)、[发布回滚](runbooks/release-rollback.md) |
| `release/` | 具体变更的验收、验证范围与交付状态 | [V20 验收](release/v20-functional-validation.md)、[训练自动恢复](release/workout-auto-recovery.md) |
| `performance/` | 实验协议、结果与边界 | [架构对照](performance/architecture-comparison-2026-09-17.md)、[V1 热点读](performance/v1-benchmark.md) |
| `performance/evidence/` | 与实验报告对应的冻结证据 | 按报告中的 run ID、日期和 revision 查找 |
| `test-plans/` | 尚需执行或分阶段执行的验证计划 | 计划不等同于已通过的测试结果 |
| `roadmap/` | 阶段规划、选型约束与后续方向 | 按文档日期阅读，不等同于当前实现 |
| `interview/` | 技术复盘与面试问答 | [面试索引](interview/README.md) |
| `resume/` | 简历及项目描述材料 | 与源码和验收证据分开维护 |

历史文档不是无用文件：版本验收、性能数字和设计结论需要原始证据支持。新增或更新结论应写明验证时间、代码 revision 和未覆盖范围；不要覆盖历史实验数据或将旧结果当成当前全量验证。

开发和验证命令统一查阅[脚本导航](../scripts/README.md)，前端说明见 [Web README](../web/README.md)。

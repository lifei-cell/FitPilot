# 项目结构与维护规则

项目采用模块化单体：后端遵循 Maven 标准布局，Web 独立构建。目录按业务职责和开发、验证、部署的用途划分。

```text
FitPilot/
├── src/
│   ├── main/java/com/fitpilot/  # 后端业务模块与公共基础设施
│   ├── main/resources/        # 配置、Flyway 迁移、提示词与评测集
│   ├── test/java/com/fitpilot/ # 与业务包对应的测试、独立基准实验
│   └── test/resources/        # 测试专用数据
├── web/
│   ├── src/api/               # HTTP 客户端与接口类型
│   ├── src/auth/              # 跨页面身份上下文
│   ├── src/components/        # 通用布局与展示组件
│   ├── src/features/          # agent、plan、workout 功能及配套状态
│   ├── src/pages/             # 页面组合
│   ├── src/test/              # 测试环境、渲染辅助、MSW
│   ├── e2e/                  # 在线浏览器场景
│   └── e2e-offline/           # 离线恢复场景
├── scripts/
│   ├── run-quality-gate.ps1   # 稳定的统一验证入口
│   ├── development/          # 本地开发、演示数据
│   ├── evaluation/           # Agent / RAG / 真实模型评测
│   ├── performance/          # 架构对比、积压恢复、写入压测
│   ├── operations/           # 故障演练与双实例恢复
│   ├── production-validation/# 生产验证及证据采集
│   └── delivery/             # 发布门禁、Kubernetes、备份和密钥演练
├── load-test/                 # k6 场景、运行器与场景回归测试
├── deploy/                    # Kubernetes、Kind、监控、模拟模型配置
├── docs/                      # 设计、运行手册、验收和历史证据
├── .github/workflows/         # CI、模型评测、发布与部署
├── .mvn/                      # Maven 配置；repository 为本地缓存
├── pom.xml / Dockerfile       # 后端构建入口
└── docker-compose*.yml        # 本地启动与双实例覆盖配置
```

## 模块边界

- 后端业务模块为 `auth / user / exercise / plan / workout / pr / analytics / notification`；AI 能力为 `agent / rag / llm / evaluation`。`common` 放通用协议、安全和错误处理；`infrastructure` 放事件与性能基础设施，`observability` 放指标，`deployment` 放迁移入口。
- 业务模块内部按需要使用 `controller / application / domain / dto / infrastructure` 等子包，不为目录对称创建空层。控制器接入请求，应用层编排事务和权限，领域层表达规则，持久化层负责存储。
- 前端功能专属组件、状态和测试放在同一 `features/<feature>/` 中；Agent 会话缓存位于 `features/agent/storage/`。跨页面身份上下文保留在 `auth/`，通用组件不承载特定业务流程。
- 测试靠近所属模块。后端测试镜像业务包，前端单元测试与被测文件同目录，浏览器场景独立放置。

## 文件生命周期

| 类型 | 位置与处理方式 |
|---|---|
| 可维护源码、配置、脚本 | 纳入 Git；移动时同步更新 import、CI、脚本路径和文档 |
| 数据库迁移、评测数据 | 属于运行契约；不能因版本号旧而删除 |
| 已发布验收结论与原始证据 | 保留日期和 revision；证据集中在 `docs/performance/evidence/`，不批量改写历史 manifest、哈希或实验补丁 |
| 临时构建与测试产物 | `target/`、`web/dist/`、`web/coverage/`、浏览器测试输出；不提交 |
| 本地评测、发布、压测输出 | `evaluation-reports/`、`release-evidence/`、`load-test/reports/`；不提交；正式引用前脱敏并归档 |
| 本地配置和工具状态 | `.env`、IDE 配置、`.repo_memory/`、`.memorax-code/`、`.claude/`；不提交，不进入后端镜像构建上下文 |

根目录保留构建与启动入口，不堆放临时日志或无含义的目录。Maven、Compose 和 Web 构建路径保持稳定；新增任务脚本进入对应用途目录。

## 本次整理

- 将根层脚本归入用途目录，保留统一质量门禁入口；同步修正脚本相对根目录、调用方和 CI。
- 将分散的 Agent 浏览器状态文件归入对应前端功能目录。
- 将 V1 三份已引用压测报告归档至 `docs/performance/evidence/v1-hot-read-20260828/`，避免后续压测覆盖历史证据；运行器仍输出到被忽略的 `load-test/reports/`。
- 清理根目录旧构建日志、JVM 崩溃日志及空的“新建文件夹”；保留有效历史文档、模型缓存和本地验收证据。

运行方式见[脚本导航](../scripts/README.md)。文档入口见[文档导航](README.md)。

### 整理验证（2026-11-01）

- `mvn test`：74 项通过，无失败或跳过；本次未运行依赖容器的完整后端集成门禁。
- Web `npm run quality`：lint、类型检查、44 项单元测试、覆盖率门禁、构建、8 项在线与 3 项离线浏览器测试全部通过。
- k6 账号隔离回归：3 项通过；所有 PowerShell 脚本语法、迁移脚本根目录及同目录依赖检查通过。
- 17 个文件迁移完成，活动文档与调用方无旧路径，新导航链接有效；3 份归档报告内容保持不变。
- 未重跑真实模型评测、压力实验、故障演练或生产部署；历史证据仍仅对应各自记录的 revision。

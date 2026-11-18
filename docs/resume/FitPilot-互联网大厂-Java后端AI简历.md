# [姓名]

**Java 后端开发工程师 / AI Agent 应用工程师**

[手机] ｜ [邮箱] ｜ [现居城市] ｜ [GitHub/个人主页] ｜ [求职状态]

## 个人概述

- 具备 Java 21、Spring Boot 3.5 后端项目实战，能够围绕领域建模、事务一致性、缓存、消息队列、数据库和可观测性完成端到端交付。
- 有 AI 应用工程经验，落地 Hybrid RAG、单 Agent Workflow、LLM Gateway、离线评测和产品效果指标；坚持“模型提议、后端授权、用户确认”的安全边界。
- 重视工程证据与故障边界，使用 Testcontainers、JaCoCo、Vitest、Playwright、SBOM、镜像扫描和发布证明建立可复现质量门禁。

## 专业技能

- **Java 后端：** Java 21、Spring Boot、Spring MVC、Spring Security、JWT、Validation、MyBatis-Plus、Flyway、JUnit 5、Mockito、Testcontainers
- **数据与中间件：** PostgreSQL、pgvector、Redis、Caffeine、Kafka、Elasticsearch；具备事务、索引、行锁、幂等、缓存一致性和最终一致性实践
- **AI / Agent：** Hybrid RAG、Parent-Child Chunk、BM25 + Vector Search、RRF、Citation、Prompt Registry、Model Router、Tool Calling、Guardrail、Human-in-the-loop、离线评测
- **工程与交付：** Docker Compose、Kubernetes/Kustomize、GitHub Actions、GHCR、CycloneDX SBOM、Trivy、Gitleaks、OpenTelemetry、Prometheus、Grafana、Loki、Tempo
- **前端协作：** React 19、TypeScript、Vite、React Query、Vitest、React Testing Library、MSW、Playwright

## 项目经历

### FitPilot——AI Native 健身训练平台

**项目周期：** [按实际参与起止时间填写]<br>
**项目角色：** [请按真实情况填写：个人项目 / 项目负责人 / 核心开发]<br>
**项目地址：** <https://github.com/lifei-cell/FitPilot><br>
**技术栈：** Java 21、Spring Boot 3.5、PostgreSQL/pgvector、Redis、Caffeine、Kafka、Elasticsearch、React、Docker、Kubernetes、OpenTelemetry

**项目简介：** 面向健身用户提供训练计划、Workout、个人纪录、训练分析、专业知识检索和 AI 辅助计划调节；采用模块化单体承载核心业务，并围绕一致性、性能、AI 安全写入、知识治理和可信交付完成工程化建设。

**主要工作与结果（STAR）：**

- **训练领域建模：** 针对训练计划持续修改会污染历史记录和统计口径的问题（S/T），将可变 `TrainingPlan` 与事实型 `Workout` 分离，开始训练时事务化复制动作、目标组次、RPE 等快照，并通过状态机、行锁、唯一约束和 Owner 查询守住核心写链路（A），保证历史训练、PR 和 Analytics 可追溯，同时降低重复完成、并发错号和 IDOR 风险（R）。

- **事件一致性与故障恢复：** 针对数据库与 Kafka 双写可能产生状态丢失或重复副作用的问题（S/T），采用 Transactional Outbox 原子提交 Workout 与事件，通过 Relay、Inbox、业务唯一约束、退避重试、DLT、数据库死信和人工回放构建可靠消费链路（A），使 Kafka 故障不回滚已完成训练，恢复后派生数据可自动追平，实现 `At-least-once + 幂等消费 + 最终一致性`（R）。

- **热点读取与流量治理：** 针对动作详情和 ACTIVE 计划的热点访问以及 Redis 单点依赖问题（S/T），构建 Caffeine L1 + Redis L2 + PostgreSQL 链路，引入空值缓存、TTL 抖动、分布式重建锁、Cache Aside 和 Redis 故障回源，本地限流作为降级兜底（A）；本机热点 GET 压测达到 998.83 req/s、0% HTTP 失败、P95 1.76ms，明确保留 6 次 dropped iterations 的容量边界（R）。

- **Hybrid RAG 与知识治理：** 针对关键词召回不足、纯向量检索术语精度偏弱及引用不可追溯问题（S/T），实现 Parent-Child Chunk、pgvector HNSW 与 Elasticsearch BM25 双路召回，通过 RRF、确定性 Rerank、Parent Context 和 Citation 返回可验证来源，并补充可信等级、不可变版本、删除传播和人工反馈审核（A）；建立 Recall@5、MRR、Citation Validity 及分类回归门禁，ES 异常时可降级为向量检索并重建索引（R）。

- **安全 Agent 与训练调节闭环：** 针对 LLM 直接执行写操作存在越权、幻觉参数和不可审计风险（S/T），将模型限制为结构化提议组件，由后端统一执行 JWT 身份继承、Tool 白名单、领域校验、Guardrail、一次性确认和持久化；会话以 PostgreSQL 为真源、Redis 为热缓存，并基于 28 天训练事实生成可解释调整（A）；实现跨设备会话与待确认动作恢复，数据不足或疼痛风险自动阻断，确认后只创建新 DRAFT，主备模型失败回退规则 Workflow 时仍不放宽安全链（R）。

- **质量、可观测与可信发布：** 针对复杂中间件和 AI 链路容易“功能可用但无法证明可靠”的问题（S/T），建立 Maven、Testcontainers、JaCoCo、ESLint、TypeScript、Vitest、Playwright、Prometheus 告警、SBOM、Gitleaks、Trivy 与镜像 Provenance 门禁（A）；历史 V20 功能基线 `9bd259c` 记录 122 个后端零跳过测试、行覆盖率 85.75%，Web 44 个组件测试和 11 个 Chromium 场景、行覆盖率 76.26%（R）。浏览器场景使用 API 桩，不等同于真实后端联调；当前提交的回归与远端发布结论分别核验。

**补充验证数据：** 历史 V5 本机 Docker Compose 30 分钟混合流量完成 118,980 次 HTTP 请求，业务成功率 99.99%，普通 API P95 10.03ms、Agent P95 30.70ms；该数据用于证明测试环境下的实现与稳定性，不表述为真实生产容量。

## 教育背景

### [学校名称]｜[专业名称]｜[学历]

[入学年月]—[毕业年月]

- [主修课程、GPA、排名、奖项；只保留与岗位相关且真实的内容]

## 其他经历（有真实内容再保留）

- **实习 / 工作经历：** [公司、团队、岗位、时间；建议每段写 2—4 条 STAR 结果，优先使用业务规模、延迟、成功率、成本、效率等数据]
- **竞赛 / 开源 / 证书：** [奖项、贡献链接、证书名称；删除普通或与岗位无关的内容]

---

## 面试自我介绍参考（不放入正式简历）

FitPilot 是一个 Java 21 和 Spring Boot 3.5 的训练记录与 AI 辅助计划调整项目。核心训练写入通过历史快照、事务内父行锁和数据库回执处理并发与离线重试；完成训练后通过 Outbox、Kafka 和 Inbox 更新个人纪录与分析，接受派生数据短暂延迟。AI 只提出结构化建议，由后端校验身份、领域规则和风险，用户确认后创建新草案。面试时我会重点展开训练一致性、可靠事件和受控 Agent，并区分当前回归、历史实验与真实生产验证。

以上为表达模板；“我设计、我实现”的内容须按本人真实贡献调整。具体数字以底部证据索引为准。

## 投递前检查（不放入正式简历）

1. 补齐姓名、联系方式、学校、时间、项目角色和真实工作经历，删除所有方括号占位符。
2. Java 后端岗位优先保留“领域建模、事件一致性、缓存、质量发布”四条；AI/Agent 岗位优先保留“事件一致性、RAG、Agent、质量发布”四条。
3. 正式简历控制在 1—2 页，每条项目经历尽量不超过三行；不要把技术栈重复写进每条经历。
4. 不使用“Exactly Once”“绝对一致”“零故障”“稳定支撑 1000 QPS”“已生产上线”等超出证据范围的表述。

## 数据证据索引（不放入正式简历）

| 结论 | revision / Run | 原始报告 |
|---|---|---|
| 2026-11-18 收尾回归及未覆盖范围 | 被测业务源码 `112b2584ed7ab08938bcd1baf16df94fb7e3154b`；本次仅修改文档 | [收尾检查](../release/closeout-2026-11-18.md) |
| 历史 V20、122 个后端测试、44 个组件测试、11 个浏览器场景 | `9bd259c7f4469826d094da1add6ef4bcad604a25`；不是本次全量复验 | [V20 功能验收](../release/v20-functional-validation.md) |
| 历史 CI、安全扫描、GHCR 多架构镜像、Digest、SBOM、Provenance | 报告记录修复 revision `c55e26d52fa74010f9d9467911aa2de3e7de3b9d`；CI `34465034299`、Release `34465790273`；本次未重新核验远端，不代表当前 HEAD 发布成功 | [远端发布验收](../release/p1-delivery-validation.md) |
| 30 分钟混合流量与告警恢复 | Run ID `20260830-091822`，历史 V5/V9 本机基线 | [V5 性能验证](../performance/v5-production-validation.md) |
| Kind 交付演练 | `1d98621891ff92d98ad57c77ff212015b641681f`，仅历史本机演练 | [V6 远端发布验收](../release/p1-delivery-validation.md#本机-kubernetes-演练历史-pass) |
| 真实 Production Delivery Gate | 无已执行 revision，`SKIPPED` | [V6 远端发布验收](../release/p1-delivery-validation.md#production-delivery-gateskipped) |

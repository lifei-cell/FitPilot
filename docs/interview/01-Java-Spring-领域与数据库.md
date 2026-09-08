# Java、Spring、领域设计与数据库

## 1. 模块化单体

### 核心原理

模块化单体仍是一个部署单元和一个进程，但按业务能力划分代码和数据访问边界。它与“所有代码随意互调的单体”不同，也不等于微服务。优势是事务简单、部署成本低、调试方便；代价是隔离主要依赖工程约束，单模块故障可能影响整个进程。

### FitPilot 实际应用

项目按 `auth / user / exercise / plan / workout / pr / analytics / rag / agent / llm` 划分。调用方向基本保持：

```text
Controller → Application Service → Domain / Repository → Mapper → PostgreSQL
```

Workout 完成后需要的 PR、Analytics、Notification 不直接塞进同步事务，而是通过领域事件异步投影。这样保留单体的开发效率，同时为高耗时或弱耦合能力建立边界。

### 为什么现在不拆微服务

- 没有独立扩缩容、团队自治或故障隔离的已验证瓶颈。
- 核心训练事务跨 `Workout + Outbox`，留在同库最简单可靠。
- 微服务会引入服务发现、网络重试、分布式事务、契约治理和多套可观测成本。
- Kafka 在这里先用于模块解耦和削峰，不是为了证明“用了微服务架构”。

### 可演进方向

当 RAG/LLM 调用量、通知吞吐或团队发布节奏出现独立瓶颈时，优先拆无状态、异步、数据边界清晰的模块。拆分前要先固定事件契约、SLO、数据归属和回滚方案。

### 高频追问

**问：模块化单体如何防止变成大泥球？** 通过包级依赖规则、只经 Service/Repository 访问数据、领域事件解耦派生动作、架构测试和代码所有权。当前项目尚未引入 ArchUnit，这是可补强项。

**问：为什么不直接上 Spring Modulith？** 当前包结构和事件边界已经能够满足规模；Modulith 可用于自动验证模块依赖和生成文档，但引入前要确认收益，不为框架而框架。

## 2. Spring IoC、AOP 与 Bean 生命周期

### 核心原理

IoC 容器负责创建 Bean、解析依赖和管理生命周期；依赖注入让对象不负责寻找依赖。Spring AOP 通常通过 JDK 动态代理或 CGLIB 代理拦截方法，事务、方法观测等能力都依赖代理边界。

FitPilot 使用构造器注入，关键方法通过 `@Transactional`、`@Observed` 和 Spring Security Filter Chain 获得横切能力。`ObservedAspect` 将 `@Observed` 方法接入 Micrometer Observation。

### 事务为什么会失效

- 同一个类内部 `this.method()` 调用绕过代理。
- 方法不是代理可拦截的方法，或对象不是 Spring Bean。
- 异常被捕获后未继续抛出，代理看不到回滚条件。
- 默认只对未检查异常回滚；检查异常需要显式配置。
- 新线程、异步任务不会自动继承原事务。
- 数据库表不是事务引擎，或外部系统根本不参与本地事务。

FitPilot 把 Workout 状态和 Outbox 都放在同一 PostgreSQL 事务中，但 Kafka 发送由事务外 Relay 完成，因为本地事务无法原子覆盖数据库和 Kafka。

### 高频追问

**问：`@Transactional` 放 Controller 上行不行？** 技术上可以，但会把 HTTP 编排和业务事务耦合，事务范围也更难控制。项目把事务放在 Application Service 用例边界。

**问：事务越大越安全吗？** 不是。大事务持锁更久、放大冲突、占用连接并增加回滚成本。核心事务只保存业务事实和 Outbox，派生计算异步化。

## 3. Spring MVC、Validation 与异常处理

请求经过 Filter → Security Filter Chain → DispatcherServlet → Controller → Service。DTO 使用 Bean Validation 做结构校验，领域规则由 Validator/Service 再校验。两层校验不能互相替代：

- Bean Validation 适合长度、范围、非空等局部规则。
- 领域校验适合跨字段、跨实体、状态机和数据库事实。

统一异常转换应把业务错误映射为稳定错误码与 HTTP 状态，避免把数据库异常或堆栈直接暴露给客户端。

**追问：为什么不能只依赖前端校验？** 前端不是信任边界，请求可被直接构造；后端必须重新校验。

## 4. Spring Security、JWT 与 Refresh Token

### 原理

JWT 由 Header、Payload、Signature 组成。签名保证完整性和来源，不提供保密性，因此不能在 Payload 放密码或敏感隐私。无状态 Access Token 便于水平扩展，但签发后在过期前很难立即撤销。

Refresh Token 用于换取新 Access Token，生命周期更长，必须可撤销、轮换并安全保存。浏览器中通常放 `HttpOnly + Secure + SameSite` Cookie，减少脚本直接读取风险。

### FitPilot 落地

- `SecurityConfig` 使用无状态 Session、BCrypt 密码哈希和 JWT Filter。
- Access Token 默认 2 小时；Refresh Token 默认 30 天。
- Refresh Token 使用高熵随机值，只以 SHA-256 摘要作为 Redis Key，刷新时执行旋转：旧 Token 删除并签发新 Token。
- Cookie 为 HttpOnly，并根据配置启用 Secure；注销时撤销 Refresh Token。
- JWT 支持 `JWT_SECRET + JWT_PREVIOUS_SECRET` 双密钥校验，用于两阶段密钥轮换；签发只使用新密钥。
- 运维 Token 使用 `MessageDigest.isEqual` 常量时间比较，避免普通字符串早停比较泄漏时间侧信道。

### 边界与追问

**问：JWT 为什么还需要 Redis？** Access Token 验签本身不依赖 Redis；Redis 只保存可撤销、可轮换的 Refresh Token。若要即时撤销 Access Token，还需黑名单、短 TTL 或 Token Version。

**问：为什么存 Refresh Token 哈希？** Redis 泄漏时攻击者不能直接拿存储值换取会话；校验时对用户 Token 做同样哈希。

**问：关闭 CSRF 是否安全？** Bearer Token 放 Authorization Header 时浏览器不会自动附带，CSRF 风险较低；但 Refresh Token 在 Cookie 中，刷新/登出接口仍需依赖 SameSite、Origin 策略或 CSRF Token 做纵深防御。项目当前主要依赖 Cookie 属性，生产可进一步验证 Origin。

**问：BCrypt 为什么自带盐？** 每次哈希生成随机盐，同一密码结果不同，抵抗彩虹表；成本因子让暴力破解更昂贵。它不是加密，无法解密回原密码。

### Operations API 的实现边界

Operations 路径由 Spring Security 统一要求 `ROLE_OPERATIONS`，专用 Filter 使用常量时间比较校验 `X-Operations-Token` 并建立运维身份；Controller 不再承担鉴权。路径级回归测试会枚举全部 Operations 映射并验证无 Token 均在进入 Controller 前返回 403，因此新增接口默认拒绝。生产环境仍应把管理面放到独立网络并叠加 mTLS 或网关身份。

## 5. 领域建模：计划与训练快照

### 业务问题

`TrainingPlan` 是未来意图，会不断修改；`Workout` 是已经发生的事实。如果 Workout 只引用当前计划，计划变化会篡改历史语义。

### 项目方案

开始训练时将动作名、顺序、目标组次、RPE、休息时间等复制到 `WorkoutExercise`。后续 Set、PR 和 Analytics 基于快照，不依赖当前计划内容。

这不是无意义冗余，而是典型的**事实快照**：用存储换审计性、可追溯性和稳定统计口径。类似设计常见于订单商品快照、合同版本和支付账单。

### 状态机与不变量

- Workout：`IN_PROGRESS → COMPLETED / CANCELLED`，完成后不能取消。
- 完成前必须至少有一个有效 Set。
- 同一用户数据库层只允许一个 `IN_PROGRESS` Workout。
- TrainingPlan 只有 DRAFT 可编辑；激活时旧 ACTIVE 归档。
- 计划更新使用 `version` 做乐观锁，冲突返回 409 要求重新加载。

**追问：为什么业务校验后还要数据库约束？** 应用校验改善错误提示，数据库约束防止并发竞态和旁路写入；两者是纵深防御。

## 6. PostgreSQL MVCC 与事务隔离

### MVCC 原理

PostgreSQL 更新通常生成新行版本，旧版本由事务可见性规则决定是否可见，VACUUM 后续回收。MVCC 让读写在很多场景下不互相阻塞，但并不意味着没有锁：更新同一行、唯一约束检查、DDL 等仍会竞争。

默认 `READ COMMITTED` 下，每条语句获取新的快照，可能出现不可重复读；`REPEATABLE READ` 固定事务快照；`SERIALIZABLE` 使用 SSI 检测危险依赖，可能主动中止事务，应用必须重试。

FitPilot 多数用例依赖短事务、行锁、乐观锁和唯一约束，而不是简单把所有事务升到 Serializable。

### 行锁应用

新增 Set 时先对对应 `workout_exercise` 执行 `SELECT ... FOR UPDATE`，再计算下一个 Set 编号；唯一约束 `(workout_exercise_id, set_number)` 再兜底。这把锁限制在单个训练动作，避免全局串行。

Outbox Relay 使用 `FOR UPDATE SKIP LOCKED` 抢占批次。多个实例同时扫描时，已被一个实例锁住的事件由其他实例跳过，从而实现并行安全领取。

### 死锁

死锁来自多个事务以不同顺序获取资源。数据库会检测并回滚其中一个事务。规避方法：统一加锁顺序、缩短事务、建立索引减少扫描锁范围、对可重试错误做有界重试。

## 7. 索引设计

### B-Tree

适合等值、范围和排序。联合索引遵循最左前缀，并要结合选择性、过滤条件、排序和回表成本分析。索引不是越多越好：每个索引都会增加写放大、磁盘占用和 VACUUM 负担。

### FitPilot 典型索引

- `(user_id, started_at DESC)`：用户训练时间线。
- `(user_id, exercise_id, achieved_at DESC)`：用户动作 PR 历史。
- 部分唯一索引 `UNIQUE(user_id) WHERE status='ACTIVE'`：每用户只有一个 ACTIVE 计划。
- 部分唯一索引 `UNIQUE(user_id) WHERE status='IN_PROGRESS'`：每用户只有一个进行中 Workout。
- `(status, next_attempt_at, id) WHERE status IN (...)`：Outbox 只索引待处理小集合。
- `(created_at, session_id) WHERE role='user'`：产品活跃指标查询。

### 为什么部分索引有价值

只覆盖查询关注的行，索引更小、缓存命中更好、写维护成本更低；但 SQL 条件必须能让优化器推导出索引谓词。

**追问：如何判断索引是否生效？** 使用 `EXPLAIN (ANALYZE, BUFFERS)`，观察执行计划、实际行数、循环次数、Heap Fetch、共享块命中与读取；不能只看是否出现 Index Scan。

## 8. 乐观锁与悲观锁

- **乐观锁：** 不先阻塞，更新时带 `version` 条件；适合读多写少、冲突低。FitPilot 计划编辑使用它，冲突返回 409。
- **悲观锁：** 先锁定再操作；适合短临界区且必须串行。FitPilot Set 编号分配使用行锁。

两者可以组合：应用用乐观锁提供交互语义，数据库唯一约束负责最终不变量。

## 9. MyBatis-Plus

MyBatis 以 SQL 为中心，映射结果到对象；MyBatis-Plus 提供基础 CRUD、分页等能力，但复杂查询仍使用明确 SQL。优点是 SQL 可控、便于优化；代价是映射与 SQL 维护成本更高，也容易出现 N+1 或动态 SQL 漏条件。

FitPilot 的 Owner 查询把 `userId + resourceId` 放入 SQL，而不是先按 ID 查出再在 Java 判断，减少 IDOR 风险和竞态窗口。

**追问：MyBatis 和 JPA 怎么选？** 领域对象复杂且希望自动脏检查、对象关系导航时 JPA 方便；SQL 性能和查询形态需要强控制时 MyBatis 更直接。项目包含大量投影、部分索引、pgvector 和运营聚合，选择 SQL 可控性更合适。

## 10. Flyway 数据库迁移

Flyway 按版本顺序执行不可变迁移，并在历史表记录版本和校验和。已在共享环境执行的迁移不应直接修改，否则校验失败且无法解释环境差异。

FitPilot 使用 V1-V17 向前迁移；Kubernetes 中迁移由独立 Job 执行，应用 Pod 关闭 Flyway，发布顺序为“迁移 → 后端 → Web”。

### Expand / Contract

生产零停机迁移应分阶段：

1. Expand：新增兼容字段/表/索引，旧代码仍能运行。
2. 发布同时兼容新旧结构的应用。
3. 回填数据并验证。
4. Contract：后续版本再删除旧结构。

**追问：为什么回滚应用但不做 Down Migration？** 数据迁移可能不可逆，自动向下迁移风险大。更安全的是保持向前兼容，让旧应用仍能使用扩展后的 Schema；必要时用新迁移修复。

## 11. 本章一段话总结

> 项目采用模块化单体控制复杂度，事务放在 Application Service 用例边界。PostgreSQL 是业务真源，通过 MVCC、短事务、局部行锁、乐观锁和部分唯一索引共同维护不变量；TrainingPlan 与 Workout 快照分离保证历史事实稳定。Spring Security 使用短期 JWT 加可旋转 Refresh Token，Owner 条件下推 SQL。Flyway 只做向前兼容迁移，生产由独立 Job 先迁移再发布应用。

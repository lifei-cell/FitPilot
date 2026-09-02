# Java、JVM、并发与网络基础

## 1. JVM 运行时内存

- **Heap：** 对象和数组主要分配区域，GC 管理。
- **Thread Stack：** 每线程私有，保存栈帧、局部变量和调用状态；栈过深可能 `StackOverflowError`。
- **Metaspace：** 类元数据，使用本地内存；动态生成/重复加载 ClassLoader 可能泄漏。
- **Direct Memory：** NIO/Netty 等使用的堆外内存，不受 `-Xmx` 直接限制，但受进程和容器内存限制。
- **Code Cache：** JIT 编译后的机器码。

容器内不能只设置 `-Xmx`：还要给 Metaspace、Direct Memory、线程栈和 Native Library 留空间。Pod Limit 2Gi 不意味着 Heap 可以设满 2Gi，否则容易被 Cgroup OOM Kill。

## 2. 对象分配与 GC

多数对象先进入 Young Generation，存活多轮后晋升 Old Generation。分代假设是“大部分对象朝生夕死”。常见回收阶段：标记存活、复制或整理、回收不可达对象。

Java 21 常见选择：

- G1：面向通用服务，把 Heap 划成 Region，以可预测停顿为目标。
- ZGC：更低停顿，适合大 Heap 和延迟敏感，但要评估吞吐和资源。
- Parallel GC：重吞吐批处理场景。

面试不能只说“用了 G1”；要结合 Allocation Rate、Young/Old GC 次数、Pause、Promotion、Heap Occupancy、CPU 和业务延迟分析。FitPilot 当前性能报告记录容器/Heap 基线，但尚无真实生产长期 GC 结论。

### 常见内存问题

- 缓存无容量上限导致 Heap 增长；项目 Caffeine 设置 Maximum Size。
- ThreadLocal 未清理，在线程池中长期存活。
- Listener/回调/静态集合持有对象。
- 大 JSON/日志和响应缓存造成瞬时分配。
- 线程过多导致 Stack 与调度成本。

排查工具包括 GC Log、JFR、Heap Dump、`jcmd`、MAT；要在受控环境采集，避免 Dump 包含敏感数据。

## 3. Java Memory Model 与 Happens-Before

JMM 定义线程之间读写的可见性和排序。编译器、CPU 可以重排，只要不破坏单线程语义。

常见 Happens-Before：

- 解锁 Happens-Before 后续对同一锁加锁。
- 对 Volatile 写 Happens-Before 后续读。
- `Thread.start` 前操作对新线程可见。
- 线程内操作 Happens-Before 另一个线程成功 `join` 后的操作。
- Transitivity 传递。

`volatile` 保证可见性和特定排序，不保证复合操作 `count++` 原子。原子计数用 `AtomicLong/LongAdder` 或锁；高竞争指标更适合 LongAdder，但 `sum()` 不是线性一致快照。

## 4. synchronized、Lock 与 CAS

- `synchronized` 提供互斥、可见性和可重入，JVM 自动释放锁。
- `ReentrantLock` 支持可中断、超时、公平锁和多个 Condition，但必须 `finally` 解锁。
- CAS 比较内存值并条件更新，适合短小原子状态；高竞争会自旋浪费 CPU，并可能遇到 ABA，可用版本戳处理。

项目本地 Token Bucket 的 `consume` 使用 `synchronized` 保护 `tokens/lastRefill` 复合状态；LLM Circuit 同样同步更新失败数和 Open 时间。跨进程并发不能用 JVM 锁，需 Redis/数据库或重新设计。

## 5. ConcurrentHashMap 与线程安全

`ConcurrentHashMap` 支持并发读写，常用 `computeIfAbsent` 原子初始化单 Key。项目 LLM Client 用它保存每个 Endpoint 的 Circuit。

但“容器线程安全”不代表其中 Value 的复合状态线程安全；因此 Circuit 内部方法仍使用 `synchronized`。迭代通常是弱一致视图，不应期待全局瞬时快照。

## 6. 线程池

核心参数：Core Pool、Max Pool、Queue、Keep Alive、Thread Factory、Reject Policy。

典型执行顺序：核心线程未满先建线程；之后入队；队列满再扩到 Max；再满触发拒绝。无界队列会让 Max Pool 失去意义，并可能以内存换延迟直到 OOM。

FitPilot 评测线程池：Core=1、Max=2、Queue=10。适合低频异步评测，队列满时默认拒绝可让调用方快速发现过载，而不是无限堆积。生产应暴露 Active、Queue Size、Completed、Reject 指标，并给任务状态写入明确失败。

### 线程数估算

- CPU 密集：接近 CPU Core 数或 `N+1`。
- IO 密集：可更多，粗略按 `N × (1 + wait/compute)`，但最终以压测、下游并发和内存验证。

线程数不是越多越好：增加上下文切换、Stack 内存和下游压力。

## 7. Virtual Thread

Java 21 Virtual Thread 适合大量阻塞式 IO 请求，以较低线程成本简化同步代码；它不提高 CPU 计算能力，也不能绕过数据库连接池和外部 Provider 配额。

FitPilot 当前使用传统 Spring MVC 与线程池，没有声明采用 Virtual Thread。若演进：

- 先确认 Spring Boot 配置与库兼容。
- 检查 `synchronized` 中长时间阻塞导致 Pinning。
- 仍需限制 DB Connection、Kafka、LLM 并发。
- 用压测比较吞吐、P95、内存和下游饱和，而不是因为 Java 21 就默认开启。

## 8. 定时任务与多实例

`@Scheduled` 在每个应用实例都会执行。FitPilot Outbox Relay 多实例安全是因为数据库 `SKIP LOCKED` 抢占；Agent 产品指标刷新是重复计算 Gauge，通常可接受；清理任务则要确认 SQL 幂等和批次。

如果任务只能全局执行一次，可使用数据库租约、ShedLock、Kubernetes CronJob 或独立 Worker。分布式锁失效时任务本身仍应幂等。

`fixedDelay` 从上次任务结束后计时，不会在单调度线程中重叠；`fixedRate` 按开始时间间隔，任务耗时长时可能追赶或并发，具体还受 Scheduler 配置影响。

## 9. CompletableFuture、异步与上下文传播

异步执行不会自动继承 Spring 事务、SecurityContext、MDC/Trace Context。需要显式传递必要的只读上下文，并避免把 ThreadLocal 用户身份带入错误任务。

项目主要通过受控 `TaskExecutor` 执行评测，并把 Run ID/状态持久化。异步任务必须具备：幂等、状态机、超时、失败落库、可重试/可取消和队列背压。

## 10. TCP、HTTP 与连接

### TCP

TCP 提供面向连接、可靠、有序的字节流，通过序列号、ACK、重传、滑动窗口和拥塞控制实现。它不保留消息边界，应用协议负责 framing。

三次握手建立双方收发能力；四次挥手分别关闭两个方向。大量短连接会增加握手、TIME_WAIT 和端口压力，因此 HTTP Keep-Alive/连接池重要。

### HTTP

- HTTP/1.1 支持 Keep-Alive，但同连接并发受限且有队头阻塞。
- HTTP/2 多路复用、Header 压缩，但 TCP 丢包仍影响同连接 Stream。
- HTTP/3 基于 QUIC/UDP，减少传输层队头阻塞和握手成本。

FitPilot LLM Client 使用 JDK `HttpClient`，设置连接超时 3 秒、请求超时 15 秒。连接超时只限制建连，请求超时覆盖等待响应；DNS、TLS、连接池和 Provider 端延迟都可能影响总耗时。

## 11. TLS

TLS 提供传输加密、完整性和服务端身份验证：通过证书链验证服务器，握手协商密钥，后续使用对称加密。生产调用 LLM 和外部依赖必须使用 HTTPS，并正确校验证书，不能为“解决测试问题”关闭校验。

mTLS 让客户端也提供证书，适合服务间强身份；它不能替代业务授权，证书只证明服务身份，不证明当前用户可操作某资源。

## 12. 超时预算

一条请求若依次调用多个依赖，外层 Timeout 必须大于合理的内层预算，但不能简单相加到很大。需要：

- 连接、读取、总请求分别设限。
- 重试计入总 Deadline。
- 下游超时小于上游，给上游留降级/清理时间。
- 客户端取消后尽可能中断无价值工作。

LLM 15 秒请求 × 多次重试 × 主备 Endpoint，最坏延迟可能很长；项目最终 Rule Fallback 保证结果，但生产还应加入总体 Deadline，避免重试链拖垮请求线程。

## 13. JSON 与序列化

JSON 可读、生态广，但缺少强 Schema、字段名冗余、数字/时间语义容易歧义。项目对 REST、事件和 LLM 结构化输出使用 JSON，并通过 DTO、Validation、Event Version 和 Prompt Version 约束。

安全注意：限制 Body/Context 大小、拒绝未知危险 Tool 字段、避免多态反序列化到任意 Class、日志脱敏。事件长期演进可评估 Avro/Protobuf + Schema Registry。

## 14. Maven 生命周期

常见阶段：`validate → compile → test → package → integration-test → verify → install → deploy`。

- Surefire 运行单元测试。
- Failsafe 在 Integration Test/Verify 阶段运行集成测试，能保证测试后清理阶段仍执行。
- JaCoCo Agent 收集覆盖率并在 Verify 检查门槛。
- CycloneDX 在 Package 生成 SBOM。
- Enforcer 在 Validate 阶段禁止跳过测试。

项目使用 `mvn verify` 而不是只跑 `test`，因为后者不会完成 Failsafe、覆盖率门禁、零跳过检查和完整构建验证。

## 15. 高频追问

**问：HashMap 为什么线程不安全？** 并发写会丢更新、读取不可见，复合操作没有原子性；实现细节随 JDK 变化，不应只背“形成环”。

**问：Volatile 能实现单例吗？** 双重检查需要 Volatile 防止对象构造与引用赋值重排；更简单安全的是静态内部类或 Enum。

**问：线程池队列满了怎么办？** 触发拒绝策略；可以 Abort、CallerRuns、丢弃或自定义。选择取决于能否丢任务和是否允许调用方背压，不能静默丢核心业务。

**问：发生 OOM 只调大 Heap 可以吗？** 不一定。先判断泄漏、流量峰值、大对象、Direct Memory 或线程数；盲目调大只会延迟故障并拉长 GC。

**问：为什么数据库连接池不能和 Virtual Thread 一样无限？** 数据库并发受 CPU、锁、IO 和服务器连接上限约束；更多连接可能增加竞争和上下文切换。

## 16. 本章一段话总结

> FitPilot 运行在 Java 21，但不把版本特性当成性能结论。JVM 内存要同时考虑 Heap、Metaspace、Direct Memory 和线程栈；并发状态用合适的锁/CAS与有界线程池，跨实例则依赖数据库/Redis和幂等。定时任务必须考虑多实例重复执行。外部 LLM 调用需要连接、请求和总体 Deadline，重试计入预算。完整验证使用 Maven Verify 串起单元、集成、覆盖率、零跳过和 SBOM。

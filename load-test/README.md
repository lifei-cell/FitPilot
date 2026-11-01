# V5 HTTP 压测

`v5-mixed.js` 为持续混合负载，`v5-burst.js` 为五分钟突发负载。
迭代比例约为读 70%、写 20%、Agent 10%；一次写迭代可能包含多个 HTTP 请求，
因此 `RATE` / `PEAK_RATE` 是迭代速率，不是 HTTP RPS。

## 账号隔离

- setup 按 `MAX_VUS` 预建独立账号及激活计划；mixed 默认 100，burst 默认 400。
- 使用 `vu.idInTest` 一对一取账号，不循环复用；动态增加的 VU 也拥有独立账号。
- 同一个 VU 顺序执行训练流程，避免不同 VU 触发“同用户已有进行中训练”的 409。
- 初始化按每秒最多约 2 个账号节奏执行，遇到 429 有限重试；setup 超时为 10 分钟。
- 初始化不计入自定义 `business_success`，但 k6 总 HTTP 指标仍包含 setup 请求。
- 测试失败仍会如实计入错误；本改动不把真实业务冲突改成成功。
- 每轮创建的账号与训练数据保留在测试数据库，避免对用户数据执行自动删除。
  容量对比应使用独立测试数据库和一致的数据基线。

## WSL 原生 Docker 低负载验证

在含修复的项目目录执行（Linux host 网络，无需依赖 `host.docker.internal`）：

```bash
docker run --rm --network host \
  -e BASE_URL=http://127.0.0.1:8080 \
  -e RATE=10 -e DURATION=1m \
  -e PRE_ALLOCATED_VUS=4 -e MAX_VUS=8 \
  -v "$PWD/load-test:/work:ro" \
  grafana/k6:0.57.0 run /work/v5-mixed.js
```

先确认 `LLM_ENABLED=false`，以上是规则 Agent 链路的低负载验证，不代表真实模型性能。
`MAX_VUS` 不得小于 `PRE_ALLOCATED_VUS`。现有脚本面向单个 k6 实例，
分布式执行需要统一规划账号池及 VU ID 范围。

Windows PowerShell 7 可使用 `run-v5.ps1` 保存报告。包装脚本会删除整个
`setup_data`，避免账号池中的 token 泄漏；直接使用 k6 `--summary-export`
时也必须删除 `setup_data` 后才能分享报告，不要输出或提交原始账号池。

## 回归检查

```bash
node --experimental-vm-modules --test load-test/v5-users.test.mjs
```

测试通过隔离的 k6 模块上下文模拟多个 VU，验证账号/计划归属、重叠训练无共享账号冲突、
动态 VU 的账号绑定、初始化失败及认证限流重试。

2026-11-01 本地验证：上述 3 项回归通过，PowerShell 语法与账号池报告脱敏验证通过；
WSL 已运行服务上，以 10 迭代/秒运行 1 分钟，预分配 4 VU、账号池 8 个，
完成 600 次迭代、820 次 HTTP 请求（含 32 次初始化），HTTP 失败 0，
业务检查 788/788 通过，普通业务请求 P95 为 15.05 ms。
这是脚本修复的低负载验证，不是容量结论；未执行五分钟 burst 高负载测试。

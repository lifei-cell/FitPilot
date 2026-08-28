# FitPilot V1 热点读取性能验证

## 验证范围

- 日期：2026-08-28
- 被测接口：`GET /api/v1/exercises/1`
- 部署：单应用实例、PostgreSQL 17、Redis 7.4，Docker Compose
- 压测端：Docker `grafana/k6:0.57.0`
- 单档持续时间：10 秒
- 链路：预热后的 Caffeine L1 热点读取
- 为隔离缓存吞吐，压测时设置 `RATE_LIMIT_ENABLED=false`
- 阈值：HTTP 失败率 `<1%`，P95 `<250ms`

## 实测结果

| 目标速率 | 实际请求 | 实际速率 | HTTP 失败率 | P95 | 最大延迟 | Dropped iterations |
|---:|---:|---:|---:|---:|---:|---:|
| 100 QPS | 1,002 | 99.52 req/s | 0% | 4.87ms | 61.65ms | 0 |
| 500 QPS | 5,001 | 499.83 req/s | 0% | 2.02ms | 27.05ms | 0 |
| 1000 QPS | 9,996 | 998.83 req/s | 0% | 1.76ms | 921.29ms | 6 |

三档均满足本次 HTTP 失败率和 P95 阈值。1000 QPS 档出现 6 次调度丢弃和一次明显长尾，因此只能说明该环境接近 1000 QPS 热点读目标，不能表述为“无损稳定支撑 1000 QPS”。

## 证据与复现

- `load-test/reports/v1-100-qps.json`
- `load-test/reports/v1-500-qps.json`
- `load-test/reports/v1-1000-qps.json`
- `load-test/v1-hot-read.js`
- `load-test/run-v1.ps1`

## 结果边界

本报告不覆盖登录、写请求、数据库未命中、复杂 Analytics、混合流量、长时间稳定性、CPU/内存上限或多实例扩展，因此不能作为系统整体容量结论。后续应增加 30 分钟以上稳态测试、读写混合模型和资源曲线采集。

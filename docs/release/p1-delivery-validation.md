# P1 真实交付门禁验收

验证日期：2026-08-31。验收对象为提交 `8c1a9ef`，结论必须区分本机一次性集群演练、GitHub 远端发布和生产集群执行。

## 已完成

- 统一质量门禁：Maven 单元测试 22 个、集成测试 12 个，合计 34 个且跳过数为 0；JaCoCo、ESLint、TypeScript、Vitest/RTL/MSW 5 个测试、生产构建和 Playwright Chromium 1 个 E2E 全部通过。
- Gitleaks 本地扫描完整 22 个提交通过；两项历史测试假值使用提交、文件、规则和行号组成的精确 fingerprint 忽略，不使用路径或正则宽泛放行。
- 一次性 Kind 集群真实执行：基线 Migration/Rollout、独立恢复库备份恢复、候选 Migration/Rollout、两个 Deployment 的 Rollback 与镜像核对、候选恢复、JWT 双密钥轮换、旧 JWT 撤销、新 Operations Token 200 与旧 Token 403；最终输出 `KIND_DELIVERY_DRILL=PASS`。
- 隔离演练结束后删除固定 Kind 集群、专用 PostgreSQL/Redis 容器和网络；数据库 URL 与轮换密钥未写入证据。
- 发布只接受成功 CI 对应的最新 `main` revision；过期 CI 结果不会执行镜像构建。CI 使用并发取消，且缓存实际生效的 `.mvn/repository`，避免旧提交覆盖标签或空缓存造成重复依赖下载。

## 远端门禁

- CI Run：`33345621572` 的 secret-scan 已通过；停止观察时 verify 仍在质量门禁中，按本次决策不再等待最终结果。
- Release Run：未确认触发；未取得应用与 Web 的 GHCR digest、发布清单和 provenance 远端证据。
- 远端验收结论：`BLOCKED`。代码已具备“成功 CI + 最新 main”双重约束，但本次放弃远端闭环，不能宣称远端 CI、安全扫描或 GHCR 发布全部完成。

## 生产边界

`Production Delivery Gate` 已实现严格 digest、精确 kube context、GitHub `production` Environment、RBAC 预检和失败恢复。当前机器没有生产 kube context 和 Environment secrets，因此本次没有操作生产集群；通过的是同脚本的一次性真实 Kubernetes 集群演练，不能表述为生产上线。

## 最终结论

交付门禁代码、本地质量验证和一次性真实 Kubernetes 演练已完成；远端 CI/GHCR 与生产环境演练均未闭环，P1 的真实生产交付结论保持 `BLOCKED`。

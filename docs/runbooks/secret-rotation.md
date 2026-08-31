# 密钥轮换

## 范围

包括 JWT、Operations Token、数据库凭据、LLM/Embedding API Key、Grafana 管理员凭据和 GHCR/Kubernetes 发布凭据。Secret 不进入镜像、ConfigMap、日志或 Git。

## 双密钥轮换流程

1. 在提供方创建新密钥，保留旧密钥；记录负责人、变更单和回收时间。
2. 第一阶段将新 JWT 写入 `JWT_SECRET`，旧 JWT 写入 `JWT_PREVIOUS_SECRET`；更新 Operations Token 后显式执行 Deployment 滚动重启，不要仅更新 Secret 或 Deployment 元数据。
3. 验证新 Pod readiness、新 Operations Token 返回 200、旧 Token 返回 403；验证 Job 和证据只记录状态码，不记录值。
4. 等待最长 JWT 有效期后删除 `JWT_PREVIOUS_SECRET` 并再次滚动重启；紧急泄漏场景可在同一门禁中立即撤销旧 JWT。
5. 检查结构化日志和 `llm_invocation`，确认没有 Secret、JWT、邮箱或密码泄漏。

生产使用 `Production Delivery Gate` 的 `secret_rotation_drill` 和 `revoke_previous_jwt` 输入。应用发放的 JWT 始终使用当前密钥，兼容窗口内只把旧密钥用于验签。

泄漏事件应立即撤销密钥、暂停相关发布凭据、轮换所有派生凭据并按访问日志界定影响范围。Operations Token 比较为常量时间，但仍必须限制网络入口并定期轮换。

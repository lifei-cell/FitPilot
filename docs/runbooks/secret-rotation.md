# 密钥轮换

## 范围

包括 JWT、Operations Token、数据库凭据、LLM/Embedding API Key、Grafana 管理员凭据和 GHCR/Kubernetes 发布凭据。Secret 不进入镜像、ConfigMap、日志或 Git。

## 双密钥轮换流程

1. 在提供方创建新密钥，保留旧密钥；记录负责人、变更单和回收时间。
2. 更新 GitHub Environment 或 Kubernetes Secret，触发滚动发布；不要在命令行输出值。
3. 验证新 Pod readiness、外部依赖连接、LLM 主备调用和 Operations API 鉴权。
4. 等待最长 JWT/连接池缓存周期后撤销旧密钥，再次验证。
5. 检查结构化日志和 `llm_invocation`，确认没有 Secret、JWT、邮箱或密码泄漏。

泄漏事件应立即撤销密钥、暂停相关发布凭据、轮换所有派生凭据并按访问日志界定影响范围。Operations Token 比较为常量时间，但仍必须限制网络入口并定期轮换。

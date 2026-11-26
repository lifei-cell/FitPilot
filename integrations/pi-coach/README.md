# FitPilot 本地 ChatGPT 订阅接入

适配器通过 `@earendil-works/pi-ai` 的 `openai` provider 为本机 FitPilot 注册并授权，使用官方 Responses API。它向现有 Java 模型网关返回 Chat Completions 兼容响应。FitPilot 继续负责检索、权限校验、计划确认及规则回退。

## 启动

在运行 Docker Engine 的同一台本地 Linux 主机或 WSL 发行版中，从仓库根目录执行。需要 Node.js 22.19 以上和 Docker Compose 2.24.4 以上。

```bash
repo="$(pwd)"
cd "$repo/integrations/pi-coach"
npm ci --ignore-scripts
npm run login
npm run models
```

在浏览器里选择账号或工作区并允许 FitPilot 使用 ChatGPT 订阅。账号需要有可用额度。使用 `npm run models` 输出的模型 ID。

```bash
export FITPILOT_PI_MODEL=gpt-6-luna
export FITPILOT_PI_BRIDGE_KEY="$(openssl rand -hex 32)"
export FITPILOT_PI_HOST="$(docker network inspect bridge --format '{{range .IPAM.Config}}{{.Gateway}}{{end}}')"
npm start &
cd "$repo"
docker compose -f docker-compose.yml -f docker-compose.pi.yml up -d --wait
```

Web 入口仅绑定 `127.0.0.1:4173`，Java 后端经 Web 代理访问，适配器监听 Docker 网关。模型、端口和适配器密钥在 Node 进程与 Compose 之间共用，可在本机保存并在重启前加载。适配器密钥与 ChatGPT 凭证各自独立。通过系统环境代理才能连接 OpenAI 时，可在启动 Node 前设置 `NODE_USE_ENV_PROXY=1`，并将回环地址和 Docker 网关加入 `NO_PROXY`。

## 验证与停用

- `npm test` 检查 Responses 流到现有模型网关的转换、JSON 合同、身份验证和限额回退。
- ChatGPT 凭证由 Pi 刷新，存放在用户的 `$XDG_STATE_HOME/fitpilot/pi-coach` 下，默认路径为 `~/.local/state/fitpilot/pi-coach`。可通过 `FITPILOT_PI_STATE_DIR` 指定独立路径。
- 停用时，用基础 Compose 配置重新创建 `app`，并停止 Node 适配器。运行 `npm run logout` 可以清除该本地授权，也可从 ChatGPT 的登录连接中撤销授权。

订阅用量受账号计划和 OpenAI 支持范围约束。公网托管或收费场景按 OpenAI 的接入流程申请。 [官方接入说明](https://developers.openai.com/siwc/token-sharing-open-source)

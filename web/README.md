# FitPilot Web

React + TypeScript + Vite 用户端，统一通过 `/api/v1` 访问 FitPilot 后端。

```bash
npm ci
npm run dev
```

- 本地开发地址：`http://localhost:4173`
- 生产构建：`npm run build`
- Compose 入口：`http://localhost:4173`
- Kubernetes 入口：`https://fitpilot.example.com`

生产镜像使用非 root Nginx，`/api` 反向代理后端，其余路径支持 SPA 回退。

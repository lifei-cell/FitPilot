# FitPilot Web

React + TypeScript + Vite 用户端，统一通过 `/api/v1` 访问 FitPilot 后端。

```bash
npm ci
npm run dev
```

- 本地开发地址：`http://localhost:4173`
- 生产构建：`npm run build`
- 统一质量门禁：`npm run quality`
- ESLint：`npm run lint`
- TypeScript：`npm run typecheck`
- Vitest / RTL / MSW：`npm run test:coverage`
- Playwright：`npm run test:e2e`
- Compose 入口：`http://localhost:4173`
- Kubernetes 入口：`https://fitpilot.example.com`

生产镜像使用非 root Nginx，`/api` 反向代理后端，其余路径支持 SPA 回退。

## 源码布局

- `src/pages/` 组合页面；`src/features/agent|plan|workout/` 保存各功能的组件、状态与单元测试。
- `src/features/agent/storage/` 保存会话与待确认操作的浏览器缓存。
- `src/api/` 维护客户端和类型；`src/auth/` 维护共享身份上下文；`src/components/` 放通用组件。
- `src/test/` 放测试辅助，`e2e/` 和 `e2e-offline/` 分别验证在线与离线场景。

完整目录规则见[项目结构](../docs/project-structure.md)。

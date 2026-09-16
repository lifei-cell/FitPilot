# FitPilot 手机 App 与应用市场上架路线

日期：2026-09-16。性质：实施计划，尚未创建 Android/iOS 工程，也未提交任何应用市场审核。

## 现状与选择

- `web/` 是 React + Vite 客户端，已有手机布局、390×844 训练流程测试，以及 V19 的本机草稿、待同步队列、数据库回执和生产构建静态资源缓存。它们仅在 Web/浏览器场景验证；没有原生生命周期、真机或商店审核记录。
- 后端 Spring Boot REST API 可复用。推荐在现有 `web/` 中接入 Capacitor，先做 Android 内测，再做 iOS。保留一份业务页面和 API 模型；当前没有必须重写为 React Native 或 Flutter 的证据。
- 目前客户端没有 Capacitor 项目、应用包和商店材料。`docs/release/p1-delivery-validation.md` 的真实 Production Delivery Gate 仍为 `SKIPPED`，因此公开上架前必须先提供稳定、可访问的正式服务。

## 实施顺序

1. **服务与手机体验**：按[外网正式服务落地清单](public-service-deployment-2026-09-16.md)确定正式域名、HTTPS、后端部署、监控与备份；在手机上走通注册、训练、断网恢复、计划、Agent。补齐安全区域、键盘遮挡、触控目标、通知/个人档案/退出入口，处理移动端返回和深链。生产环境禁止演示账号、占位域名和开发接口。
2. **Android 安装包**：在 `web/` 增加 Capacitor 配置，`webDir` 指向 Vite 产物 `dist`；固定应用 ID 与版本号，生成并同步 Android 工程，使用 Android Studio 在模拟器和真机调试。打包的是本地静态资源，不把线上网页 URL 当成整个 App。至少增加可见的手机功能，例如训练中断恢复、网络状态与本地提醒。
3. **接口与认证适配**：`web/src/api/client.ts` 目前写死 `/api/v1`，靠 Web 代理；原生容器必须使用明确的 HTTPS API 基地址。`AuthController` 的 Refresh Token 是 `HttpOnly; SameSite=Strict` Cookie，原生页面与 API 跨源时不能假定现有刷新流程成立。先定义 Web/原生两套传输与会话适配接口，再实现原生安全存储和令牌轮换；不能把长期令牌搬到 `localStorage`，也不能为绕过跨源问题直接放宽 Cookie 属性。验证登录、冷启动、过期刷新、退出和账户切换。
4. **离线与生命周期**：保留 V19 `Client-Operation-Id` 和数据库回执，真机验证锁屏、来电、杀进程、升级、弱网和重复提交。现有 `localStorage` 队列不是无限可靠的长期存储；若作为核心使用场景，迁入持久存储并设计备份/迁移，保留冲突导出与人工处理。不要承诺系统后台自动同步，除非原生后台任务另行实现并验证。
5. **上架能力与材料**：实现应用内账号删除及关联数据处理、外部可访问的删除申请入口；发布隐私政策、用户协议和联系方式，梳理训练、身体指标、Agent 对话等数据的收集、保留与删除。准备图标、截图、年龄分级、测试账号和审核说明，按最小权限原则检查 SDK。健身建议避免宣称医学诊疗能力。
6. **渠道发布**：Android 先在测试轨道试用，再生成签名 AAB 提交 Google Play；面向中国境内提供互联网信息服务时先核对并办理 APP 备案，再按目标国内安卓渠道逐一提交。iOS 需要 macOS/Xcode、Apple Developer 账号、TestFlight、签名与 App Store Connect 审核。发布时核对各渠道当期 SDK/API、资料和测试要求。

## 验收门槛

- 同一正式后端支持 Web 和 App；生产迁移、发布、回滚、备份恢复以及基本监控均有本次 revision 的证据。V19 后端需先于支持离线同步的 App 上线；V20 Relay 切换遵循 `docs/release/outbox-claim-ownership.md` 的停旧版步骤。
- 真机覆盖 Android 首装、升级、冷启动、锁屏/后台/杀进程、网络中断与恢复、响应丢失重放、登录过期和账户切换；iOS 上架前在 iPhone 重做同等关键场景。
- 无未处理的阻断性崩溃、数据丢失或重复训练组；待同步状态清楚，故障可恢复或导出。确认隐私披露与实际代码、第三方 SDK 一致；商店审核结果单独记录，不能由本地测试推断通过。

## 当前需要修改的入口

`web/src/api/client.ts`（API 基地址与会话适配）、`web/src/auth/AuthContext.tsx`（原生会话生命周期）、`web/src/components/AppShell.tsx`（移动导航）、`web/src/App.tsx`（返回键/深链）、`web/src/features/workout/`（持久化与真机恢复）、`src/main/java/com/fitpilot/auth/controller/AuthController.java`（现有 Cookie 契约）；账号删除需要新增后端用例与前端入口。

## 官方依据（提交时复核）

- [Capacitor 官方文档](https://capacitorjs.com/docs)
- [Apple App Review Guidelines：4.2 最低功能、5.1 隐私](https://developer.apple.com/app-store/review/guidelines/)
- [Apple 应用内账号删除](https://developer.apple.com/support/offering-account-deletion-in-your-app/)
- [Google Play 账号删除](https://support.google.com/googleplay/android-developer/answer/13327111)
- [Google Play 新建应用与 AAB/测试](https://support.google.com/googleplay/android-developer/answer/9859152)
- [Google Play 目标 API 级别](https://support.google.com/googleplay/android-developer/answer/11926878)
- [工信部 APP 备案通知](https://www.miit.gov.cn/jgsj/xgj/wjfb/art/2023/art_dd783a581c9644a4aee10afa582811db.html)

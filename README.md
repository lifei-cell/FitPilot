# FitPilot V0.1

FitPilot V0 是一个 Java 21 + Spring Boot 3 的模块化单体健身训练后端。当前版本已跑通：注册登录 → 用户画像 → 动作库 → 训练计划 → Workout 快照 → 训练组记录 → 完成训练 → PR → 统计。

## 架构

```text
REST Controller
      ↓
Application Service（用例编排、事务、owner 校验）
      ↓
Domain（计划校验、Epley PR 计算）
      ↓
Repository → MyBatis-Plus Mapper → PostgreSQL
```

模块按 `auth / user / exercise / plan / workout / pr / analytics` 划分。训练开始时把计划动作复制为 WorkoutExercise 快照，之后修改计划不会污染历史事实。所有私有资源均使用 `userId + resourceId` 查询，避免 IDOR。

## 技术栈

- Java 21、Spring Boot 3.5、Spring MVC、Validation、Spring Security JWT
- MyBatis-Plus、PostgreSQL、Flyway、Redis（V0 基础设施预留）
- SpringDoc OpenAPI、JUnit 5、Mockito、Testcontainers
- Docker Compose

## 一键启动

1. 复制 `.env.example` 为 `.env`，替换数据库密码和至少 32 字节的 JWT 密钥。
2. 启动并验证：

```powershell
docker compose up --build -d
docker compose ps
curl.exe http://localhost:8080/actuator/health
```

Swagger：<http://localhost:8080/swagger-ui.html>

只在本机开发时，也可先启动 PostgreSQL/Redis，再执行：

```powershell
$env:DB_PASSWORD = "your-password"
$env:JWT_SECRET = "your-at-least-32-byte-development-secret"
mvn spring-boot:run
```

项目自带 `.mvn/maven.config`，依赖缓存写入项目内，避免 Windows 全局 Maven 仓库权限问题。

## 核心 API

| 能力 | API |
|---|---|
| 注册 / 登录 | `POST /api/v1/auth/register`、`POST /api/v1/auth/login` |
| 用户 / 身体数据 | `GET /api/v1/users/me`、`PUT /profile`、`POST/GET /body-metrics` |
| 动作库 | `GET /api/v1/exercises`、`GET /api/v1/exercises/{id}` |
| 训练计划 | `POST/GET /api/v1/training-plans`、`POST /{id}/activate` |
| Workout | `POST/GET /api/v1/workouts`、动作/Set 增删改、取消、完成 |
| PR | `GET /api/v1/personal-records`、动作当前 PR / 历史 |
| Analytics | `GET /api/v1/analytics/overview`、动作进度、体重趋势 |

除注册、登录、动作库、Swagger 和健康检查外，请发送 `Authorization: Bearer <token>`。

## 关键业务保证

- 计划创建、计划激活、Workout 完成均为事务；同一用户数据库层只允许一个 ACTIVE 计划。
- Set 编号通过数据库行锁串行分配，并由唯一约束兜底。
- 完成 Workout 幂等；PR 来源与类型有唯一约束，不会重复生成。
- PR 使用 Epley 公式，支持最大重量、Estimated 1RM、3/5/8/10RM、单组最大容量。
- Flyway 管理全部表、外键、查询索引和 50 个动作种子。

## 测试

```powershell
mvn test
mvn verify
```

`mvn test` 执行领域和服务单元测试；`mvn verify` 额外执行 PostgreSQL Testcontainers 端到端链路及计划事务回滚测试。没有可用 Docker 时集成测试会明确跳过。

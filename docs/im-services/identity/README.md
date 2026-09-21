# platform-identity-service

> E0 服务文档基线。职责/端口/入口按当前脚手架实现填写，指标/告警阈值待可观测体系接入后校准。

## 职责

- SaaS 账号注册、登录与会话管理（SaaS 账号权威，不依赖 `im-user-service`）
- 凭据、OAuth / 第三方登录（IM OAuth 绑定）
- 用户信息同步与账号绑定
- 认证上下文：`GET /auth/contexts`、`POST /auth/context/select`

## 端口

- 服务直连：`4100`（`server.port`）
- 网关公开入口：`/api/v1/identity/**`、`/api/v1/auth/contexts`、`/api/v1/auth/context/select`

## 入口（controller 路径，不带 /api）

- `/identity/accounts`（POST 注册）
- `/identity/login`（POST 登录）
- `/identity/oauth/im/callback`（POST **IM OAuth 授权码回调**：校验 code/PKCE/state/nonce，建立按应用隔离的 HttpOnly SaaS Session 并写 C/B Cookie —— OAuth/OIDC 入口）
- `/identity/oauth/im/bind`（POST ~~IM OAuth 绑定~~ **已废弃：恒返回 `410 LEGACY_BIND_DISABLED`**，新流程走上方 `/identity/oauth/im/callback`）
- `/auth/contexts`（GET 上下文列表）
- `/auth/context/select`（POST 上下文选择）

## 依赖

- MySQL：库 `gv_saas`，Flyway history 表 `flyway_schema_history_identity`
- 外部 HTTP：`platform-tenant-service`（`TENANT_SERVICE_BASE_URL`，默认 `http://localhost:4110`）、IM 服务（`IM_SERVICE_BASE_URL`，默认 `http://localhost:3100`）
- Redis：无
- MQ：无

## 关键指标

- `http_server_requests_seconds_count`：登录 / 注册 / 上下文选择吞吐与错误率
- 登录成功 / 失败计数（按 `tenantId` 聚合）
- 认证上下文查询 P99 时延

## 告警

- 登录失败率 > 阈值（疑似爆破 / 配置错误）
- `platform-tenant-service` 或 IM 服务调用错误率升高
- 数据库连接池耗尽或 Flyway 迁移失败

## 关联 ID

- `Authorization: Bearer <accessToken>`：登录 / IM OAuth 绑定签发的签名 JWT（subject=accountId），`/auth/contexts` 与 `/auth/context/select` 校验签名后取 accountId
- `X-Tenant-Context`：租户上下文（`tenantId` / `organizationId` / `storeId` / `accountId` / `authorizationVersion`）
- 账号 ID：登录 / 注册 / 绑定链路贯穿
- `X-IM-Service-Request-Id`：内部服务调用请求 ID

## 故障处置

- 登录异常：核对 MySQL `gv_saas` 可用性与 `flyway_schema_history_identity` 迁移状态
- 上下文查询失败：核对 `platform-tenant-service`（4110）健康与 IAM 数据
- OAuth 绑定失败：核对 IM 服务（3100）可达性及 OAuth 配置

## 状态

- 已落库（E1）：注册 / 登录 / 绑定 / 上下文选择已实现；登录与 IM OAuth 绑定签发签名 JWT Access Token，`/auth/contexts` + `/auth/context/select` 改为校验 Bearer Token（不再信任明文 X-Account-Id）

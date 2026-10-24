# platform-identity-service

[中文](README.md) | [English](README.en.md)

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

## 目标身份模型

当前服务已提供 SaaS 账号、登录标识、IM OAuth 绑定与 C/B 会话。
目标模型见：

- [身份与访问模型](../../../40-domain/identity/IDENTITY_ACCESS_MODEL.md)
- [身份数据模型](../../../40-domain/identity/IDENTITY_SCHEMA.md)
- [ADR-0015](../../../20-architecture/adr/0015-identity-federation-tenant-iam.md)

关键增量：`idt_account.account_type` 只保留 `PLATFORM_OPERATOR/USER`；员工/客户由 `tnt_membership.membership_type` 决定；角色绑定改为 membership。

## OAuth 会话应用维度

`/identity/oauth/im/callback` 先解析实际 `appId`（请求值或服务配置），token 交换、OAuth 绑定、SaaS session 与 C/B cookie 全部使用同一个 resolved appId。
`idt_oauth_link` 按 `(provider, app_id, open_id)` 唯一；IM 当前 openId 全局，C/B 共享同一 SaaS account，角色由会员身份区分。

## OAuth 应用注册与会话族

- `saas.apps.consumer` / `saas.apps.backend` 显式登记 C/B 应用。
- OAuth 回调与 `/auth/session` 先校验 appId；未登记返回 400 `OAUTH_APP_INVALID`。
- C/B cookie 族由注册表决定，不再用后缀猜测。
- `/auth/contexts`、`/auth/context/select` 校验会话 appId 与请求 appId 一致。

配置：

```text
SAAS_APPS_CONSUMER=saas-a380-c,saas-hotel-c,saas-ktv-c
SAAS_APPS_BACKEND=saas-a380-h5,saas-hotel-b,saas-ktv-b
```

B 端应用未绑定员工：`/auth/contexts` 返回 `403 STAFF_NOT_BOUND`，不返回空上下文冒充可用。

S3 会话入口要求：

- `/auth/session`、`/auth/contexts`、`/auth/context/select` 的 `appId` 必填，缺失返回 `400 SAAS_APP_REQUIRED`。
- B 应用 OAuth 回调阶段校验 STAFF 成员；未绑定直接 `403 STAFF_NOT_BOUND`，不创建会话、不种 cookie。
- C 应用回调授权后校验已有 ACTIVE CUSTOMER membership；未绑定返回 403 CUSTOMER_NOT_BOUND。S3 不建客户，首登建客户留到 S5。

S3 解绑/重绑：

- `POST /identity/oauth/im/unbind?app_id=` 解绑当前 app、删除当前会话、清 cookie。
- 只影响目标 app，不解绑其他应用。
- 重新授权同 app 时复用 REVOKED link 并置 ACTIVE，避免唯一键 409。

解绑会话撤销：

- `SaasUserSessionStore` 维护 `accountId + appId -> sessionIds` 索引。
- 解绑按 account+app 批量删除会话与 CSRF，再清 cookie。

C/B audience（酒店链路）：

- 身份上下文 token 写入 `clientApp` claim（`saas-c` / `saas-b`）。
- SDK 验签后写入 `ClientAudienceHolder`。
- 酒店 `/admin/hotel/**` 要求 `saas-b`，`/business/hotel/catalog/**` 要求 `saas-c`；跨族返回 `403 CLIENT_AUDIENCE_FORBIDDEN`。

S3 解绑权限撤销：

- `DELETE /internal/iam/consumer/{appId}/authorization` 撤销 `tnt_app_user_authorization` 并 `evict(accountId)`。
- identity C 端 unbind 调用该内部接口；B 端不动 consumer 授权。

SAAS_APPS_* 无默认值；未配置时所有 app 拒绝，C/B 双登记启动失败。

回调响应新增（向后兼容）：

```text
accountId
appId
membershipId
tenantId
```

B 取 ACTIVE STAFF，C 取 ACTIVE CUSTOMER；未绑定在回调阶段 403，不建 session。

回调会话上下文：

- OAuth 回调创建 session 时写入 `membershipId`、`tenantId`、`membershipType`。
- 回调响应同步返回 `accountId/appId/membershipId/tenantId`。
- C context 以 ACTIVE CUSTOMER membership 为前置门禁。

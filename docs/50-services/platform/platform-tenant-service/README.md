# platform-tenant-service

[中文](README.md) | [English](README.en.md)

> E0 服务文档基线。职责/端口/入口按当前脚手架实现填写，指标/告警阈值待可观测体系接入后校准。

## 职责

- 租户、组织、门店、商户主体、收银终端建模
- IAM：角色、权限、用户角色分配
- 权限 / 数据范围快照缓存与授权变更逐出
- 套餐（pricing plan）管理

## 端口

- 服务直连：`4110`（`server.port`）
- 网关公开入口：`/api/v1/admin/platform/**`、`/api/v1/admin/tenant/**`、`/api/v1/admin/iam/**`、`/api/v1/admin/pricing-plans/**`

## 入口（controller 路径，不带 /api）

- `/admin/iam/**`（权限 / 角色 / 用户角色）
- `/internal/iam/**`（内部 IAM 查询：权限 / 上下文）
- `/admin/platform/tenants`（POST 租户）
- `/admin/pricing-plans`、`/internal/pricing-plans`（套餐）

## 依赖

- MySQL：库 `gv_saas`，Flyway history 表 `flyway_schema_history_tenant`
- Redis：权限快照缓存（`PermissionSnapshotCache`，当前依赖 Spring Boot 默认连接，未在 `application.yml` 显式配置）
- MQ：无

## 关键指标

- IAM 权限查询吞吐与 P99 时延
- 权限快照缓存命中率
- 授权变更逐出次数

## 告警

- 权限查询错误率升高（IAM 不可用会阻断跨服务授权）
- Redis 不可用导致快照缓存回源放大
- 租户 / 角色 / 权限写操作失败

## 关联 ID

- `X-Tenant-Context`：租户上下文（`tenantId` / `organizationId` / `storeId` / `accountId`）
- `accountId`：权限 / 上下文查询主体
- `authorizationVersion`：授权快照版本，用于缓存逐出判断

## 故障处置

- 权限查询失败：核对 `gv_saas` IAM 表与授权版本缓存
- 缓存不一致：触发授权变更逐出后重查
- 租户创建失败：核对 Flyway `flyway_schema_history_tenant` 迁移

## 角色与权限接口

```text
GET  /api/v1/admin/iam/roles/catalog        当前工作区可见角色（平台或租户）
GET  /api/v1/admin/iam/roles/assignable     租户可分配角色（预置 + 本租户自定义）
POST /api/v1/admin/iam/roles                创建平台/租户自定义角色
GET  /api/v1/admin/iam/permissions          权限目录
GET  /api/v1/admin/iam/roles/{id}/permissions
POST /api/v1/admin/iam/roles/{id}/permissions
DELETE /api/v1/admin/iam/roles/{id}/accounts/{accountId}
PUT    /api/v1/admin/iam/roles/{id}/accounts/{accountId}/scope
```

约束：预置角色只读；租户只能创建 `TENANT/STORE` 角色；权限必须满足角色 `scopeLevel` 与权限 `grantableLevels`。

## 工作区配置

```text
GET /api/v1/admin/tenant/workspace-mode
PUT /api/v1/admin/tenant/workspace-mode
```

`storeWorkspaceMode`：

```text
INDEPENDENT     门店独立工作台（默认）
TENANT_CONTEXT  门店作为租户后台内的经营上下文
```

内部端点 `GET /internal/tenant/{tenantId}/workspace-mode` 供后台登录解析 workspace 使用。

## 状态

- 骨架已落库（E1）：租户 / IAM / 套餐接口已实现（见 `SAAS_PLATFORM_07_EXECUTION.md`）

## 身份与成员模型

租户成员、角色绑定、应用安装和权益的目标模型见：

- [身份与访问模型](../../../40-domain/identity/IDENTITY_ACCESS_MODEL.md)
- [身份数据模型](../../../40-domain/identity/IDENTITY_SCHEMA.md)
- [ADR-0015](../../../20-architecture/adr/0015-identity-federation-tenant-iam.md)

当前 `iam_user_role` 为兼容路径；目标使用 `iam_role_binding` 绑定 membership，不再按全局 account 绑定租户/门店角色。

## 租户成员 API（S2）

- `GET /admin/memberships?type=&status=`：当前租户成员列表
- `POST /admin/memberships`：创建员工/客户成员身份
- `POST /admin/memberships/{id}/status`：ACTIVE/SUSPENDED/LEFT，版本递增
- `GET /internal/iam/accounts/{accountId}/memberships`：登录期跨租户查询，显式 accountId
- `GET /internal/iam/memberships/{id}`：成员详情

`tnt_membership` 是角色绑定主体；`iam_role_binding` 必须绑定 membership，不直接绑定全局 account。

## 成员角色绑定 API（S2）

- `GET /admin/memberships/{membershipId}/role-bindings`
- `POST /admin/memberships/{membershipId}/role-bindings`
- `POST /admin/role-bindings/{id}/revoke`

绑定规则：主体必须是 STAFF membership；角色作用域必须与绑定作用域一致；门店/组织 scope 必须带对应 ID；跨租户成员拒绝。

## 角色绑定写读切换（S2）

- `/admin/iam/user-roles` 现在写 `iam_role_binding`，主体为 STAFF membership。
- 权限快照改为 `iam_role_binding -> iam_role -> iam_permission`，平台角色用 ACCOUNT subject。
- 撤销/scope 调整按 membership 绑定处理。
- `iam_user_role` 不再由新 API 写入；旧服务方法保留为迁移期兼容，S6 删除。

## IAM 角色列表租户校验

`GET /admin/iam/roles?tenantId=` 只允许平台上下文或当前租户；跨租户返回 403 `TENANT_SCOPE_DENIED`。
旧 `iam_user_role` 写方法返回 410 `LEGACY_ROLE_WRITE_DISABLED`，新写路径为成员角色绑定。

## 平台绑定与旧表冻结

- `/admin/iam/accounts/{accountId}/user-roles` 同时返回 membership 绑定和平台 ACCOUNT 绑定 id。
- `DELETE /admin/iam/user-roles/{id}` 可撤销两类绑定，并按账号逐出权限快照。
- 旧 `iam_user_role` 写方法统一返回 410；迁移脚本保留生效窗口和 SELF 映射。

新写路径已记录 IAM 审计：membership create/status、role_binding assign/revoke/scope。


S4：/admin/apps 安装/卸载，/admin/entitlements 只读，/admin/platform/entitlements 平台开通。


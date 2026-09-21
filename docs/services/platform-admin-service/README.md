# platform-admin-service

SaaS 后台 BFF（统一登录入口 + 平台运营后台 + 租户后台），端口 4150。

## 职责

- 统一登录入口（统一入口 BFF）：登录后按账号权限返回可访问后台列表，路由到 IM 后台 / 平台运营后台 / 租户后台。
- 菜单聚合（菜单 BFF）：按 scope（PLATFORM / TENANT）返回后台菜单树。
- 平台/租户上下文解析：解析 X-Tenant-Context（租户/组织/门店/账号/授权版本），注入 TenantContextHolder。
- 只做参数校验、权限上下文透传、响应聚合与旧契约适配；**不拥有 SaaS 领域权威表**（无 Mapper/Flyway/权威写表）。

## 统一入口原则（SAAS_PLATFORM_02 §9.1）

三个后台（IM 后台 / 平台运营后台 / 租户后台）共用一个登录入口；登录后按账号权限路由：

- 账号拥有多个后台权限 → 展示后台入口选择页。
- 账号仅拥有单个后台权限 → 直接进入该后台。
- 权限判断由后端返回，前端不自行推断，避免后台入口分散杂乱。

前端落地：gv_saas_admin（统一入口 + SaaS 后台），gv_chat_admin（IM 后台，由统一入口链接进入）。

## 当前接口

- GET /admin/backends — 返回当前账号可访问后台列表（脚手架暂返回全部三类：im / platform / tenant，后续按 IAM 权限过滤）。
- GET /admin/menus?scope=PLATFORM|TENANT — 返回对应后台菜单树（脚手架静态，后续按角色 + 权限码过滤）。

## 网关路由（gateway）

- saas-admin：/api/v1/admin/menus/**、/api/v1/admin/backends/** → PLATFORM_ADMIN_URI（默认 http://localhost:4150，StripPrefix=2）。

## 说明

- 服务内 Controller 路径不带 /api；网关 StripPrefix=2 负责剥离 /api/v1。
- 脚手架菜单/后台列表为静态；真实实现需接入 platform-identity-service（SaaS 账号认证）与 IAM 权限码，统一入口的 SSO 令牌握手属 E-IM 范围，尚未实施。
- 审计占位：TenantContextHolder 已注入上下文，后续在此记录 iam_audit_log。

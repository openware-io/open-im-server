# 身份与访问模型

> 状态：`accepted target model`（2026-10-23，`develop/2.4.0-hotel`）
> 适用范围：IM、SaaS C 端、SaaS B 端、平台运营、租户/门店、业务 API
> 决策：ADR-0015；旧模型 `ACCOUNT_PERMISSION_MODEL.md` 中与本文件冲突的部分以本文件为准。

## 1. 目标

```text
IM 与 SaaS 两套账号域
OAuth 2.0/OIDC 联邦
CUSTOMER / STAFF 成员身份分离
平台 / 租户 / 门店三级角色
权限、权益、数据范围分离
可独立部署、独立运营、独立分拆
```

## 2. 领域与所有权

| 领域 | 服务 | 表 | 说明 |
| --- | --- | --- | --- |
| IM 用户 | `im-user-service` | `user` | IM 账号 |
| IM 开放平台 | `im-user-service` | `open_application`、`open_application_scope`、`open_user_authorization` | 应用注册、scope、用户授权 |
| SaaS 账号 | `platform-identity-service` | `idt_account`、`idt_login_identity`、`idt_oauth_link` | SaaS 账号与登录方式 |
| 租户成员 | `platform-tenant-service` | `tnt_membership`、`tnt_staff` | 员工/客户在租户内的身份 |
| 客户档案 | `platform-customer-service` | `cst_member`（增加 `membership_id`） | 复用现有客户主档 |
| 角色权限 | `platform-tenant-service` | `iam_role`、`iam_permission`、`iam_role_permission`、`iam_role_binding` | 授权 |
| 应用安装 | `platform-tenant-service` | `tnt_app_installation` | 租户安装的 SaaS 应用 |
| 权益 | `platform-tenant-service` | `tnt_entitlement` | 租户购买/开通的业务能力 |
| 会话 | `platform-identity-service` | Redis | C/B/Platform 会话 |
| 平台代操作 | `platform-tenant-service` | `iam_support_grant` | 限时代操作授权 |

规则：

```text
跨服务只保存对方 ID，不建立跨库外键
IM 不拥有 SaaS 业务表
SaaS 不拥有 IM 消息/关系表
授权只通过 API/事件/令牌声明协作
```

## 3. 主体模型

### 3.1 IM 用户

```text
im_user.id
open_id（当前 IM 全局 im_<userId>；app_id 为 OAuth client 维度）
```

### 3.2 SaaS 账号

```text
idt_account
  id
  status
  account_type = PLATFORM_OPERATOR | USER
```

账号是全局的，不携带 tenant_id。  
员工/客户属性由租户成员身份决定，不由账号类型决定。

### 3.3 租户成员

```text
tnt_membership
  id
  account_id
  tenant_id
  membership_type = STAFF | CUSTOMER
  status
  member_no
```

唯一键：

```text
(account_id, tenant_id, membership_type)
```

同一账号可以：

```text
在租户 A 是 STAFF
在租户 B 是 CUSTOMER
在租户 A 的多个门店任职
```

### 3.4 员工/客户档案

```text
tnt_staff
  membership_id
  employee_no
  display_name_cipher
  status

cst_member（现有客户主档）
  membership_id
  account_id
  member_no
  phone_cipher
  phone_digest
  status
```

不新建 `cst_customer`；避免两套客户主档。`cst_member` 已承载手机号密文与检索摘要。

- 员工和客户分表；
- 不把 C 端用户塞进员工表；
- 不把员工当客户会员。

### 3.5 平台运营

```text
idt_account.account_type = PLATFORM_OPERATOR
```

平台运营没有租户 membership。  
平台角色绑定：

```text
subject_type = ACCOUNT
subject_id = account_id
scope_type = PLATFORM
tenant_id = 0
store_id = 0
```

## 4. 角色、权限、权益、数据范围

### 4.1 角色

```text
iam_role
  tenant_id      NULL = 平台角色
  scope_level    PLATFORM / TENANT / STORE
  role_type      PRESET / CUSTOM
  code
  name
```

规则：

```text
平台角色只能平台创建/授予
租户可自定义 TENANT/STORE 角色
门店角色必须绑定门店
预置角色只读
```

### 4.2 权限

```text
iam_permission
  code             hotel.booking.create
  domain_code      hotel
  resource         booking
  action           create
  scope_level      STORE
  grantable_levels PLATFORM,TENANT,STORE
```

权限是能力目录。  
角色-权限只允许在 `grantable_levels` 范围内授予。

### 4.3 角色绑定

```text
iam_role_binding
  subject_type   ACCOUNT | MEMBERSHIP
  subject_id
  role_id
  scope_type     PLATFORM | TENANT | ORGANIZATION | STORE | SELF
  tenant_id
  organization_id
  store_id
  authorization_version
  status
  effective_from
  effective_to
```

约束：

```text
PLATFORM     → subject_type=ACCOUNT, tenant_id=0, organization_id=0, store_id=0
TENANT       → subject_type=MEMBERSHIP, tenant_id>0, organization_id=0, store_id=0
ORGANIZATION → subject_type=MEMBERSHIP, tenant_id>0, organization_id>0, store_id=0
STORE        → subject_type=MEMBERSHIP, tenant_id>0, organization_id>=0, store_id>0
SELF         → subject_type=MEMBERSHIP, tenant_id>0, organization_id=0, store_id=0
```

唯一键：

```text
(subject_type, subject_id, role_id, scope_type, tenant_id, organization_id, store_id)
```

禁止：

```text
按 account_id 直接绑定租户/门店角色
角色绑定的 tenant/store 与角色 scope 不一致
超出 grantable_levels 的权限授予
```

授权版本：

```text
tnt_membership.authorization_version
iam_role_binding.authorization_version
```

角色绑定/成员状态变更时递增，权限快照版本取两者最大值；旧上下文失效。

### 4.4 权益

```text
tnt_entitlement
  tenant_id
  feature_code     HOTEL / KTV / ...
  status
  effective_from
  effective_to
```

权益是商业开通，不是权限。  
最终可用：

```text
已安装应用
∩ 租户权益
∩ 角色权限
∩ 数据范围
∩ 当前 scope
```

## 5. 应用安装

IM 开放平台已有表，沿用不重复建：

```text
open_application
  app_id
  app_type
  app_name
  callback_url
  status

open_application_scope
  application_id
  scope

open_user_authorization
  application_id
  user_id
  open_id
  scope
  status
```

SaaS 租户安装：

```text
tnt_app_installation
  tenant_id
  app_id
  status
  installed_at
```

不新增重复的 IM 应用表；当前 `open_` 前缀属于挂起架构决策，沿用现有表。

服务入口只展示：

```text
当前租户已安装
当前用户已授权
当前业务类型匹配
```

## 6. 授权流程

### 6.1 C 端从 IM 进入

```text
OpenChat 运营服务
  → 酒店 C 应用
  → GVBridge.login / OAuth code
  → SaaS /identity/oauth/im/callback
  → IM 换 openId
  → 查 idt_oauth_link(provider=IM, app_id, open_id)
  → 查 tnt_membership(CUSTOMER)
  → 签发 SaaS C 会话
```

未绑定客户：

```text
允许首次授权创建 SaaS customer
必须完成手机号绑定/验证
```

### 6.2 B 端从 IM 进入

```text
OpenChat 运营服务
  → 酒店 B 应用
  → OAuth code
  → SaaS 查 idt_oauth_link
  → 查 tnt_membership(STAFF)
  → 查 tnt_staff
  → 查角色/门店
  → 签发 SaaS B 会话
```

未绑定员工：

```text
403 STAFF_NOT_BOUND
提示联系租户管理员邀请
禁止自动创建员工
```

### 6.3 SaaS 独立登录

```text
手机号/密码
企业 SSO
→ idt_login_identity
→ idt_account
→ 选择/进入租户成员身份
```

独立登录与 IM OAuth 是同一个 SaaS 账号的不同登录方式，不是统一账号。

## 6.4 租户拦截器契约

新表在 `PlatformTenantLineHandler` / MyBatis 拦截器中的策略：

| 表 | 策略 |
| --- | --- |
| `tnt_membership` | 登录期按 account 跨租户查全部成员：`@InterceptorIgnore` + 显式 `account_id`；租户内列表：显式 `tenant_id` |
| `tnt_staff` | 租户内表，默认拦截；按 membership 查询必须带 tenant |
| `iam_role_binding` | 默认拦截；平台绑定 `tenant_id=0` 必须 `@InterceptorIgnore` + 显式 `subject_type/subject_id` |
| `cst_member` | 租户内表，默认拦截；按 membership 查询必须带 tenant |

要求：

```text
禁止裸 selectList 依赖上下文猜测租户
跨租户查询必须显式 account_id + @InterceptorIgnore
平台行 tenant_id=0 必须显式 scope=PLATFORM
四组测试：无上下文、租户 A、租户 B、平台
```

## 7. 会话

三类会话，cookie/audience 分离：
```text
saas_c_session        audience=saas-c
saas_b_session        audience=saas-b
saas_platform_session audience=saas-platform
```

会话上下文：

```text
account_id
membership_id
tenant_id
organization_id
store_id
scope_type
roles
permissions
authz_version
```

规则：

```text
C 会话不能调用 B API
B 会话不能调用平台 API
平台会话默认不能进入租户 API
跨租户访问 404/403
客户端不能自报 tenant/store/role
```

## 8. API 授权

### 8.1 入口守卫

```text
Gateway
  → 校验会话
  → 校验 audience/scope
  → 注入签名上下文
```

### 8.2 业务服务

```text
TenantContextFilter
  → 解析签名上下文
  → 校验 tenant/store
PermissionGuard.require(code)
  → 校验权限
  → 校验数据范围
  → 校验权益
```

### 8.3 服务间

```text
HMAC / mTLS / service identity
不使用用户会话
```

## 9. 平台代操作

```text
iam_support_grant
  operator_account_id
  tenant_id
  scope
  reason
  expires_at
  status
```

要求：

```text
显式申请/审批
限时
限租户
单独审计
禁止默认跨租户
```

## 10. 工程结构

### 10.1 identity-service

```text
api/controller
  OauthController
  AccountController
  ContextController
application
  AccountApplicationService
  OauthCallbackApplicationService
  SessionApplicationService
domain
  account/Account
  account/LoginIdentity
  account/OauthLink
  session/SessionContext
infra/persistence
  AccountMapper / AccountPo
  LoginIdentityMapper
  OauthLinkMapper
```

### 10.2 tenant-service

```text
api/controller
  MembershipController
  RoleBindingController
  EntitlementController
  AppInstallationController
application
  MembershipApplicationService
  RoleAuthorizationApplicationService
  EntitlementApplicationService
domain
  membership/Membership
  iam/Role / Permission / RoleBinding
  entitlement/Entitlement
  app/AppInstallation
infra/persistence
  tnt_membership / tnt_staff
  iam_role_binding
  tnt_entitlement
  tnt_app_installation
```

### 10.3 customer-service

```text
domain/customer/Customer
application/CustomerApplicationService
infra/persistence/cst_member
```

### 10.4 IM

```text
im-user-service
  domain/openplatform/OpenApp
  domain/openplatform/UserAppGrant
  api/controller/OauthController
```

依赖方向：

```text
identity -> tenant/customer (internal API)
tenant -> customer (internal API, optional)
IM 不依赖 SaaS 业务
SaaS 不依赖 IM 业务表
```

## 11. 验收矩阵

```text
C token 调 B API → 403
B token 调 C API → 403
租户 A token 访问租户 B → 404/403
门店 A token 访问门店 B → 404/403
未绑定员工 OAuth → 403
未安装应用入口不可见
无权益 API → 403
租户自定义角色不能授平台权限
平台角色不能绑定租户
代操作无 grant → 403
代操作过期 → 403
```

## 12. 分阶段实施

```text
S0 文档/ADR/DDL 评审
S1 idt_oauth_link 加 app_id；tnt_membership/tnt_staff
S2 cst_member.membership_id；iam_role_binding
S3 应用注册/租户安装/权益
S4 OAuth C/B session 分离
S5 酒店/ktv 应用注册
S6 旧 iam_user_role 冻结、迁移
S7 删除旧兼容层
```

## 13. 相关文档

- [ADR-0015](../../20-architecture/adr/0015-identity-federation-tenant-iam.md)
- [身份数据模型](IDENTITY_SCHEMA.md)
- [账号权限体系](../ACCOUNT_PERMISSION_MODEL.md)
- [工作区角色模型](../../20-architecture/adr/0014-workspace-role-model.md)

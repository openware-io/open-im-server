# ADR 0015：IM 与 SaaS 身份联邦、租户成员与授权模型

## Status / 状态

**accepted（目标模型，2026-10-23）**。本 ADR 确定 IM 与 SaaS 分域、OAuth 联邦、C/B 身份分离、租户成员与角色绑定模型；替代“IM 与 SaaS 共用账号/会话”的临时做法。

## Context / 背景

当前工程已经存在：

- `platform-identity-service`：`idt_account`、`idt_login_identity`、`idt_oauth_link`、SaaS 会话；
- `platform-tenant-service`：`tnt_*`、`iam_role`、`iam_permission`、`iam_user_role`；
- `im-user-service`：IM 用户、开放平台 OAuth；
- `open-chat-app`：IM C 端与部分 SaaS B 端页面混在一起；
- `open-saas-admin`：SaaS B 端 Web。

问题不是缺少 OAuth，而是缺少稳定的身份与授权分域：

1. `idt_account.account_type` 把员工/客户做成全局单一类型，无法表达同一自然人在 A 租户是员工、在 B 租户是客户。
2. `iam_user_role` 直接绑定 `account_id`，没有租户成员身份作为授权主体。
3. C 端 customer、B 端 staff、平台运营没有统一边界。
4. IM 应用安装、租户权益、角色权限没有形成一条可校验链。
5. 酒店 C/B 端绕过 OpenChat 运营服务和 OAuth，直接使用 mock 或 admin 会话。
6. 酒店数据挂在 KTV 门店下，业务类型边界错误。

## Decision / 决策

### 1. 两套账号域，OAuth 联邦

```text
IM 账号域：user + open_application + open_user_authorization
SaaS 账号域：idt_account + login_identity + oauth_link
```

- IM 不保存 SaaS account_id 作为业务主键；
- SaaS 不保存 IM userId 作为业务主键；
- 双方只交换 `app_id + open_id + scope + authorization code`；
- 不共享账号表、不共享会话、不做跨库 JOIN。

### 2. SaaS 账号与租户成员分离

```text
idt_account                全局账号
tnt_membership             租户成员身份（STAFF / CUSTOMER）
tnt_staff                  员工档案
cst_customer               客户档案
```

- 账号是全局的；
- 成员身份是租户内的；
- 同一账号可以在不同租户拥有不同成员类型；
- 授权主体是 membership，不是全局 account。

`idt_account.account_type` 只允许表达平台侧属性：

```text
PLATFORM_OPERATOR
USER
```

不再用 `EMPLOYEE/CUSTOMER` 做全局唯一类型。

### 3. 角色绑定到成员身份

```text
平台角色：subject = ACCOUNT，scope = PLATFORM，tenant_id = 0, organization_id = 0
租户角色：subject = MEMBERSHIP，scope = TENANT
组织角色：subject = MEMBERSHIP，scope = ORGANIZATION
门店角色：subject = MEMBERSHIP，scope = STORE
```

`iam_user_role` 由 `iam_role_binding` 替代，禁止再按 account 绑定租户/门店角色。

### 4. C/B 应用分离

```text
saas-hotel-c：CUSTOMER，scope = C
saas-hotel-b：STAFF，scope = B
```

- 不同 appId、不同 audience、不同 session cookie；
- C 端不能调用 B 端 API；
- B 端不能因为 IM OAuth 成功自动获得员工权限；
- 员工必须预先存在并被邀请/绑定。

### 5. 应用安装与权益

```text
open_application          IM 应用注册（已有）
open_application_scope    IM 应用 scope（已有）
open_user_authorization   IM 用户授权（已有）
tnt_app_installation      SaaS 租户安装应用（S3）
tnt_entitlement           SaaS 租户购买/开通权益（S3，先与既有 tnt_business_entitlement 合并口径）
```

最终可用：

```text
应用已安装 ∩ 租户有权益 ∩ 成员有角色权限 ∩ 当前 scope 匹配
```

### 6. OAuth 流程

```text
OpenChat 运营服务
  → 打开 SaaS H5/小程序
  → GVBridge.login / OAuth code
  → SaaS /identity/oauth/im/callback
  → IM /oauth/token 换 openId
  → 查 idt_oauth_link(app_id, open_id)
  → 查 tnt_membership
  → 签发 SaaS C/B 会话
```

B 端未绑定员工：拒绝，不自动建员工。  
C 端未绑定客户：按产品策略创建 customer，必须绑定手机号/验证。

### 7. 平台代操作

平台运营默认不能进入租户业务。  
代操作必须：

```text
显式 grant
限时
限租户
全审计
单独 scope
```

## Consequences / 后果

正面：

- IM 与 SaaS 可独立部署、独立账号、独立运营、独立分拆；
- C/B 权限边界清晰；
- 租户隔离、员工邀请、离职回收可审计；
- 后续增加 SaaS 独立登录、企业 SSO、SCIM 不需要改业务表。

代价：

- 需要新增 membership / role_binding / app_installation / entitlement；
- 旧 `iam_user_role`、`saa_admin_account` 需要迁移或冻结；
- B 端移动/页面需要从 IM 原生迁到 SaaS 应用；
- 酒店数据需要迁到独立 HOTEL 门店。

## Migration / 迁移

```text
Phase 0：本 ADR + 设计文档 + DDL 评审
Phase 1：identity 增加 oauth app_id；tenant 增加 membership/staff/role_binding；customer 在 cst_member 增加 membership_id
Phase 2：OAuth code 回调写 membership；C/B session 分离
Phase 3：应用注册、租户安装、权益、服务入口按安装/权益过滤
Phase 4：酒店门店 businessType=HOTEL，迁移测试数据
Phase 5：冻结 iam_user_role/saa_admin_account 旧路径，切换新绑定
Phase 6：删除兼容层
```

## Verification / 验证

- 跨租户访问 403/404 矩阵；
- C 端 token 调 B 端 API 401/403；
- B 端未绑定员工 OAuth 403；
- 同一账号在 A/B 租户成员类型不同；
- 角色绑定不能授予超出 scope 的权限；
- 平台代操作必须带 grant 且审计；
- Flyway 空库/增量迁移通过；
- 文档可达、链接、G52 通过。

## References / 参考

- [账号权限体系](../../40-domain/ACCOUNT_PERMISSION_MODEL.md)
- [身份与访问模型](../../40-domain/identity/IDENTITY_ACCESS_MODEL.md)
- [身份数据模型](../../40-domain/identity/IDENTITY_SCHEMA.md)
- [ADR-0014 工作区角色模型](0014-workspace-role-model.md)
- [数据库迁移规范](../../80-development/standards/23_DATABASE_MIGRATION_STANDARD.md)
- [DDD 服务工程规范](../../80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)

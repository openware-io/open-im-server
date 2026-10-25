# 租户/门店/C 端账号与登录现状

> 状态：`P0 implemented; C standalone login still gap`（2026-10-25）
> 关联：`IDENTITY_ACCESS_MODEL.md` §6、§6.3

## 1. 平台新增租户

入口：

```text
/saas/#/admin/platform/tenants
```

当前创建内容：

```text
tenantCode
name
defaultLocale
defaultTimezone
```

新租户初始状态为 `PENDING`；平台管理员启用后，租户管理员才能正常登录。
平台租户列表提供启用/停用操作（`PATCH /api/v1/admin/platform/tenants/{id}/status`）。

平台创建成功后，同一流程继续创建初始管理员：

```text
tnt_tenant
idt_account / idt_login_identity
tnt_membership(STAFF)
tnt_staff
iam_role_binding(tenant.owner)
cons_admin_account
```

接口：

```text
POST /api/v1/admin/platform/tenants
POST /api/v1/admin/staff/bootstrap-owner
```

`bootstrap-owner` 只允许平台运营调用，按显式 tenantId 创建首个
`tenant.owner`，不依赖平台上下文。失败时前端明确提示“租户已创建、
管理员创建失败”，用相同租户编码重试即可；随后在租户列表点“启用”。

## 2. 已有租户新增账号

租户管理员入口：

```text
/saas/#/login
-> 租户后台
-> 运营人员
-> 新增运营人员
```

创建内容：

```text
cons_admin_account
idt_account + idt_login_identity
tnt_membership(STAFF)
tnt_staff
iam_role_binding
```

作用域：

```text
TENANT：租户级运营人员
STORE：门店级店长/收银/财务
```

登录：

```text
/saas/#/login
-> 选择租户/门店上下文
```

门店不是独立账号体系，而是 `iam_role_binding.scope_type=STORE + store_id`。

## 2.1 租户后台开通客户 C 登录

租户后台“新建客户”支持填写可选 **C 端密码**：

```text
电话 + C 端密码
-> POST /api/v1/admin/customers/c-login
-> idt_account / idt_login_identity(PHONE)
-> tnt_membership(CUSTOMER)
-> tnt_app_user_authorization(saas-a380-c)
-> 再 POST /api/v1/business/members 建档并写 accountId
```

这样客户可以直接到 `/c/` 用手机号 + 密码登录，不需要先走 IM OAuth。

未填密码时仍按旧流程只建客户档案，不创建 C 登录账号。

## 3. C 端客户

`/c/` 在没有 IM Bridge、也没有 SaaS 会话时显示独立登录页：

```text
手机号/邮箱 + 密码
-> POST /api/v1/identity/login
-> POST /api/v1/auth/session?appId=saas-a380-c
-> POST /api/v1/auth/context/select
-> /c/
```

账号必须已有 CUSTOMER membership，并已授权 C 应用；否则登录会提示
“该账号还没有 C 端身份”。首次客户绑定仍从 OpenChat 完成。

入口：

```text
/c/
```

认证：

```text
Android IM WebView
  -> GVBridge.login
  -> OAuth code
  -> /api/v1/identity/oauth/im/callback
  -> CUSTOMER membership
  -> SaaS C 会话
```

浏览器：

```text
/c/ -> /oauth/authorize -> 回调 -> C 会话
```

本地 Kind 有 `/api/v1/dev/saas/session` 开发态自动登录。

没有：

```text
C 端注册页
C 端验证码登录
```

## 4. 目标设计

`IDENTITY_ACCESS_MODEL.md` §6.3 已定义 SaaS 独立登录：

```text
手机号/密码 / 企业 SSO
-> idt_login_identity
-> idt_account
-> 选择/进入租户成员身份
```

C 端独立登录属于设计内能力，当前界面尚未落地。

## 5. 待补顺序

```text
P0 新增租户时创建初始管理员
   已实现：表单字段、bootstrap-owner、identity/membership/tenant.owner/BFF
   首次登录强制改密尚未实现（P0.1）

P1 C 端独立登录/注册
   已完成：手机号/邮箱 + 密码登录页
   已完成：租户后台新建客户时开通 C 登录
   未完成：C 端公开注册、验证码登录
   登录后选择 CUSTOMER membership
   再进入 /c/

P2 门店独立入口
   仅当业务需要独立门店品牌页时增加
```

P0 是当前“新租户不可用”的根因，优先级最高。

# 租户/门店/C 端账号与登录现状

> 状态：`current implementation + gaps`（2026-10-25）
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

落库：

```text
tnt_tenant
```

当前**不创建**：

```text
idt_account
idt_login_identity
tnt_membership
tnt_staff
iam_role_binding
cons_admin_account
```

因此新租户没有初始管理员，无法登录租户后台。

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

## 3. C 端客户

当前没有独立 C 端登录页。

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
C 端手机号/密码登录页
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
   平台表单增加 username/password/displayName
   创建 identity + membership + tenant.owner + BFF account
   首次登录强制改密
   任一步失败要回滚或明确提示，不能留下空租户

P1 C 端独立登录/注册
   手机号/密码或验证码
   登录后选择 CUSTOMER membership
   再进入 /c/

P2 门店独立入口
   仅当业务需要独立门店品牌页时增加
```

P0 是当前“新租户不可用”的根因，优先级最高。

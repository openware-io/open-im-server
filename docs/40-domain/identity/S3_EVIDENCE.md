# S3 OAuth C/B 会话证据

> 环境：Kind `open-im-local`，identity/tenant 使用当前分支新镜像
> 时间：2026-10-24
> 方式：真实 HTTP + MySQL 只读核对；未使用 mock

## 1. appId 必填

```text
GET /auth/session           -> 400 SAAS_APP_REQUIRED
GET /auth/contexts          -> 400 SAAS_APP_REQUIRED
GET /auth/csrf              -> 400 SAAS_APP_REQUIRED
```

## 2. 未登记 app

```text
GET /auth/contexts?appId=saas-unknown
-> 400 OAUTH_APP_INVALID "未登记的 SaaS 应用"
```

## 3. C 会话

```text
POST /dev/saas/session?appId=saas-a380-c -> 200
GET  /auth/session?appId=saas-a380-c     -> authenticated=true accountId=101
GET  /auth/contexts?appId=saas-a380-c    -> consumer:saas-a380-c:100:100:100
GET  /auth/csrf?appId=saas-a380-c        -> 200 token
```

## 4. C/B cookie 隔离

C cookie 请求 B：

```text
GET /auth/session?appId=saas-a380-h5 -> authenticated=false
POST /auth/logout?appId=saas-a380-h5 -> 403 CSRF_TOKEN_INVALID
```

没有跨族复用 C 会话。

## 5. B 未绑定员工

```text
POST /dev/saas/session?appId=saas-a380-h5 -> 200（仅调试会话）
GET  /auth/contexts?appId=saas-a380-h5    -> 403 STAFF_NOT_BOUND
```

真实 OAuth 回调在创建 session 前已执行同一 STAFF/角色校验。

## 6. unbind

准备：真实 `idt_oauth_link(account_id=101, app_id=saas-a380-c, status=ACTIVE)`。

```text
POST /identity/oauth/im/unbind?app_id=saas-a380-c
     X-CSRF-Token: <valid>
-> 200
```

无 CSRF：

```text
POST /identity/oauth/im/unbind?app_id=saas-a380-c
-> 403 CSRF_TOKEN_INVALID
```

解绑后：

```text
GET /auth/session?appId=saas-a380-c -> authenticated=false
idt_oauth_link.status = REVOKED
```

## 7. 审计

```text
open_audit.audit_log
action=iam.oauth.unbind
resource_type=oauth_link
resource_id=saas-a380-c
result=SUCCESS
```

## 8. 未覆盖

```text
真实 IM OAuth code 回调（当前环境无可用测试 IM 授权码）
CUSTOMER membership + cst_member 首登建客户
非酒店全量 API audience 矩阵
Android 真机
```

本证据不替代 S4–S7；S3 仍未签字。

## 9. C membership 门禁（2026-10-24）

`	ext
无 CUSTOMER membership -> 403 CUSTOMER_NOT_BOUND
插入 ACTIVE CUSTOMER membership -> /auth/contexts 200 consumer:saas-a380-c:100:100:100
`

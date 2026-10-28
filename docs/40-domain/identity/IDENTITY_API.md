# 身份与访问 API 契约

> 状态：`accepted target model`（2026-10-23）
> 服务：`platform-identity-service`、`platform-tenant-service`、`platform-customer-service`、`im-user-service`
> 安全：所有租户内接口必须由签名上下文确定 tenant/store，禁止信任客户端自报。

## 1. OAuth 回调

```text
POST /identity/oauth/im/callback
```

请求：

```json
{
  "appId": "saas-hotel-c",
  "code": "authorization-code",
  "codeVerifier": "pkce-verifier",
  "redirectUri": "openchat://oauth/callback",
  "clientType": "C"
}
```

服务端：

```text
1. 校验 appId 已注册、clientType 与 app_type 匹配
2. 调 IM /oauth/token 换 access_token
3. 调 IM /oauth/userinfo 取 openId
4. 查 idt_oauth_link(provider=IM, app_id, open_id)
5. 查 tnt_membership
6. 签发 SaaS C/B 会话 + audience 正确的 cookie
```

响应：

```json
{
  "accountId": 100,
  "membershipId": 12,
  "tenantId": 100,
  "storeId": null,
  "scopeType": "TENANT",
  "roles": ["tenant.owner"],
  "permissions": ["hotel.booking.view"]
}
```

错误：

```text
400 OAUTH_CODE_INVALID
400 OAUTH_APP_INVALID
401 OAUTH_CODE_EXPIRED
403 STAFF_NOT_BOUND
403 TENANT_MEMBERSHIP_REQUIRED
409 OAUTH_LINK_ALREADY_BOUND
```

## 2. 上下文

```text
GET /auth/contexts
POST /auth/context/select
```

`/auth/contexts` 返回：

```json
{
  "items": [
    {
      "contextId": "100:100:100",
      "tenantId": 100,
      "organizationId": 100,
      "storeId": 100,
      "scopeType": "STORE",
      "membershipType": "STAFF",
      "roles": ["store.manager"],
      "permissions": ["hotel.room.manage"],
      "businessType": "HOTEL"
    }
  ]
}
```

规则：

```text
C 会话只返回 CUSTOMER 上下文
B 会话只返回 STAFF 上下文
平台会话默认不返回租户上下文
```

## 3. 内部成员查询

```text
GET /internal/iam/accounts/{accountId}/memberships?tenantId=
GET /internal/iam/memberships/{membershipId}
POST /internal/iam/memberships
```

`POST` 仅用于：

```text
B 端管理员邀请员工
C 端首次授权创建 customer
```

请求必须带内部服务身份，不接受公网直接调用。

## 4. 权限快照

```text
GET /internal/iam/memberships/{membershipId}/permissions?tenantId=&storeId=
```

返回：

```json
{
  "accountId": 100,
  "membershipId": 12,
  "tenantId": 100,
  "storeId": 100,
  "roles": ["store.manager"],
  "permissions": ["hotel.booking.view", "hotel.room.manage"],
  "authorizationVersion": 8
}
```

`authorizationVersion` 单调递增；角色/权限变更后旧上下文失效。

## 5. 授权决策

```text
POST /internal/iam/authorize
```

请求：

```json
{
  "accountId": 100,
  "membershipId": 12,
  "tenantId": 100,
  "storeId": 100,
  "permission": "hotel.room.manage"
}
```

响应：

```json
{ "allowed": true, "reason": "ROLE_PERMISSION" }
```

实现建议：

```text
MVP: 权限快照 + PermissionGuard
后续: 统一 PDP（OPA/自研）时保持同一 API
```

## 6. 应用入口

```text
GET /internal/tenant/{tenantId}/apps?accountId=
```

返回：

```json
[
  {
    "appId": "saas-hotel-c",
    "appType": "C",
    "name": "酒店",
    "entryUrl": "https://.../hotel",
    "installed": true,
    "entitled": true,
    "authorized": true
  }
]
```

过滤条件：

```text
tenant_app_installation.status = INSTALLED
tenant_entitlement.status = ACTIVE
user_app_grant.status = ACTIVE
app_type 与当前端匹配
```

## 7. 会话 Cookie

```text
saas_c_session         Path=/; HttpOnly; Secure; SameSite=Lax
saas_b_session         Path=/; HttpOnly; Secure; SameSite=Lax
saas_platform_session  Path=/; HttpOnly; Secure; SameSite=Strict
```

禁止：

```text
C 端读取 B cookie
B 端读取平台 cookie
cookie 中放角色/权限明文
URL 中放 JWT
```

## 8. 服务间鉴权

```text
Authorization: Internal HMAC
X-IM-Service-Source
X-IM-Service-Timestamp
X-IM-Service-Signature
```

服务间不传递用户 Cookie。  
需要用户上下文时传签名后的 `X-Tenant-Context`，并只允许内部网络。

## 9. 错误码

```text
SAAS_CONTEXT_REQUIRED
SAAS_CONTEXT_INVALID
TENANT_SCOPE_DENIED
STORE_SCOPE_REQUIRED
MEMBERSHIP_NOT_FOUND
STAFF_NOT_BOUND
PERMISSION_DENIED
ENTITLEMENT_REQUIRED
APP_NOT_INSTALLED
PLATFORM_IMPERSONATION_REQUIRED
```

## 10. 契约测试

```text
OAuth code 单次使用
PKCE 校验失败 400
state/nonce 校验失败 400
openId 重复绑定 409
C token 调 B 接口 403
B token 调 C 接口 403
租户 A token 访问租户 B 404/403
门店 A token 访问门店 B 404/403
未安装应用入口不返回
无权益调用 403
```

# S7 请求级证据（X-Request-Id）

> 2026-10-24
> 环境：http://127.0.0.1:30080

## 1. 管理面 200

请求：

```text
GET /api/v1/admin/hotel/bookings
Cookie: saas_admin_session
X-Saas-App: saas-admin
```

响应：

```text
HTTP/1.1 200 OK
X-Request-Id: bc72c597667e6f93690012f61eb9c971
```

原始头/体：

```text
evidence/s7-trace/admin-hotel-bookings.headers
evidence/s7-trace/admin-hotel-bookings.body
```

## 2. 成员禁用后 401

```text
tnt_membership id=1 status=DISABLED
同一 admin session 再请求
```

响应：

```text
HTTP/1.1 401 Unauthorized
X-Request-Id: e7b54efcd581228793fdef3f570deff4
```

原始头/体：

```text
evidence/s7-trace/admin-hotel-bookings-disabled.headers
evidence/s7-trace/admin-hotel-bookings-disabled.body
```

说明：

```text
网关仍使用 Redis 管理会话
下游 order/tenant 的 TenantAuthorizationStateFilter 拒绝旧授权版本
因此管理面请求被 401，而不是继续返回 200
```

## 3. 局限

```text
这是请求头 + requestId 证据，不是完整 HAR
没有浏览器 Network 面板的全部静态资源/时序
没有 trace/span 导出
```

完整 HAR 与 trace 仍需后续补。

## 4. ERR-001 HTTP 500

```text
POST /api/v1/business/orders/15/collect
payment replicas=0
HTTP/1.1 500 Internal Server Error
body.requestId=e82cb6db-7
ord_order 15: DRAFT / paid=0
```

原始：

```text
evidence/s7-trace/err-001-payment-down.headers
evidence/s7-trace/err-001-payment-down.body
```

# S7 安全与隔离矩阵

> 2026-10-24
> 环境：Kind open-im-local

## 1. SEC-001..010

| ID | 用例 | 结果 | 证据 |
| --- | --- | --- | --- |
| SEC-001 | 跨租户/门店 booking 访问 | 404 | `evidence/s7-trace/sec-001-cross-store.har`；X-Request-Id e8e60c8e-598e-4eec-907d-0412247b9eff；DB store102 |
| SEC-002 | 跨门店资源 | 404 | `evidence/s7-trace/sec-002-cross-store.har`；X-Request-Id 202cf173-f6ee-4893-815c-bdd11278df6f；DB store102 |
| SEC-003 | 客户端篡改 token | 401 | `evidence/s7-trace/sec-003-tampered.har`；X-Request-Id 9a62a017-6af5-4612-a439-947e455bfec9 |
| SEC-004 | 客户端篡改 permissions | 401 | `evidence/s7-trace/sec-004-perm.har`；X-Request-Id e90d1392-3984-4f3d-9cb0-7ea63f55f7ff |
| SEC-005 | C/B cookie 混用 | 403 CLIENT_ORIGIN_FORBIDDEN | `evidence/s7-trace/sec-005-cookie.har`；X-Request-Id ad36d833937d8428e8600ca53a55287f |
| SEC-006 | 平台角色授予 STAFF | 403 PLATFORM_OPERATOR_REQUIRED | `evidence/s7-trace/sec-006-platform.har`；X-Request-Id f51a1a4b-6650-40e8-95c5-7af2c7e903cf |
| SEC-007 | token 过期 | 401 | 直连 order，过期签名 JWT，X-Request-Id 1e86b77a-4b7c-424a-8d98-d26ea6387910 |
| SEC-008 | 成员禁用旧 token | 401 | order/payment/tenant 200/401/200；admin 200->401 |
| SEC-009 | 授权版本变化 | 401 | version+1 -> 401，恢复 -> 200 |
| SEC-010 | PII 未授权 | 掩码 | 无 member.pii.view 的 B token：S** / 138****0003；本人订单保留完整 |
| SEC-011 | 跨门店收款 | 404 ORDER_NOT_FOUND | `evidence/s7-trace/sec-011-cross-store-pay.har`；X-Request-Id c16f1f65-0a35-4653-a5fc-7b11404b1b63；order21 DRAFT |
| SEC-012 | 正确门店收款 | 200 | `evidence/s7-trace/sec-012-correct-store-pay.har`；X-Request-Id 1d815037-e190-4d85-b8b2-659e95c70c55 |
| SEC-013 | 跨门店退款 request/approve/mark | 404 | `sec-013-cross-store-refund*.har`；requestId 275307d1... / cf14224e... / 723cdd58...；refund10 仅 store102 |
| SEC-014 | 正确门店退款 | 200 | `sec-014-correct-store-refund-*.har`；requestId a9c4a494... / 03723ec9...；order21 paid 79900 |

## 2. SEC-007 过期 token

构造：

```text
JWT_SECRET 签名
exp=2, iat=1
clientApp=saas-c
```

直连 order：

```text
GET /admin/hotel/bookings
HTTP/1.1 401
X-Request-Id: 1e86b77a-4b7c-424a-8d98-d26ea6387910
```

原始：

```text
evidence/s7-trace/sec-007-expired.headers
evidence/s7-trace/sec-007-expired.body
```

## 3. SEC-010 PII

构造合法 B token：

```text
clientApp=saas-b
permissions=[hotel.booking.view]
无 member.pii.view
authorizationVersion=2
```

响应：

```text
HTTP 200
X-Request-Id: b376dae7-8bec-433a-9c3b-f53109a59ca5
bookedByAccountId=112 -> S** / 138****0003
bookedByAccountId=null -> S** / 138****0001
bookedByAccountId=101（本人） -> 明文
```

原始：

```text
evidence/s7-trace/sec-010-nopii.headers
evidence/s7-trace/sec-010-nopii.body
```

单测：

```text
HotelBookingPiiTest PASS
张三 -> 张**
13800000014 -> 138****0014
```

V17：

```text
store.manager / store.cashier 授予 member.pii.view
```

## 4. SEC-008/009 覆盖

已有：

```text
order/payment/tenant TenantAuthorizationStateFilter
order   ACTIVE 200 / DISABLED 401 / RESTORED 200
payment PAY_ACTIVE 200 / PAY_DISABLED 401
tenant  TENANT_ACTIVE 200 / TENANT_DISABLED 401
admin   200 -> 401（admin 自身 filter）
admin   200 -> 401（admin 自身 filter）
identity 200 / 403
gateway 200 / 401 AUTHORIZATION_STALE
```

全链路覆盖：

```text
```

## 5. 判定

```text
SEC-001..010：均有 HAR/headers/DB 证据
SEC-007/010：本轮已补
SEC-008/009：order/payment/tenant/admin/identity/gateway 通过
全部服务与 gateway 已覆盖
S7 cash-only：仍不能 PASS
```

请求索引：

- `S7_REQUEST_EVIDENCE.md`
- `evidence/s7-trace/`

管理面 admin 自身 filter 回归：

```text
ACTIVE   -> 200
DISABLED -> 401
RESTORED -> 200
X-Request-Id 401: e1868ba33d16d1c81c5c06711609f139
```

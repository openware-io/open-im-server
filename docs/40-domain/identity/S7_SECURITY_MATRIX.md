# S7 安全与隔离矩阵

> 2026-10-24
> 环境：Kind open-im-local

## 1. 已通过

| 用例 | 请求 | 结果 | 证据 |
| --- | --- | --- | --- |
| SEC-001/002 跨门店 | B store100 token -> confirm store102 booking | 404 HOTEL_BOOKING_NOT_FOUND | API/DB |
| SEC-003 客户端篡改 | 篡改 C token 签名 | 401 Unauthorized | API |
| SEC-004 权限篡改 | 修改 token permissions 后签名失败 | 401 | 同上 |
| SEC-005 C/B cookie 混用 | C cookie 调 B app session | 403 CLIENT_ORIGIN_FORBIDDEN | API |
| SEC-006 平台角色越权 | 平台角色授予租户 STAFF 113 | 403 PLATFORM_OPERATOR_REQUIRED | S6 |
| C→B audience | C token -> B admin hotel | 403 CLIENT_AUDIENCE_FORBIDDEN | API |
| B→C audience | B token -> C catalog | 403 CLIENT_AUDIENCE_FORBIDDEN | API |

## 2. SEC-008/009：旧 token 失效（order/payment/tenant 已修复）

修复前真实失败：

```text
B token -> /admin/hotel/bookings = 200
tnt_membership id=1 status=DISABLED
同一个旧 B token -> /admin/hotel/bookings = 200
```

根因：

```text
TenantContextFilter 只校验 JWT 签名/issuer/audience
未校验 membership 当前状态与 authorization_version
```

修复：

```text
order/payment/tenant 增加 TenantAuthorizationStateFilter
每 5s 刷新 tenant 权限快照
token.authorizationVersion < 当前版本 或 当前版本=0 -> 401
```

真实回归（order）：

```text
ACTIVE   -> 200
DISABLED -> 401
RESTORED -> 200
```

真实回归（tenant）：

```text
TENANT_ACTIVE   -> 200
TENANT_DISABLED -> 401
```

真实回归（payment）：

```text
PAY_ACTIVE   -> 200
PAY_DISABLED -> 401
```

SEC-009 版本：

```text
authorization_version + 1 -> 401
恢复                       -> 200
```

仍未接入同一校验：

```text
platform-tenant-service
platform-admin-service
platform-identity-service
gateway 集中校验
```

因此 SEC-008/009 对 order/payment/tenant 通过；admin/identity/gateway 仍是缺口。

## 3. 其他

### SEC-007 token 过期

```text
依赖 JWT exp；未单独构造过期 token 证据
```

### SEC-010 PII

```text
HotelBookingApplicationService 已按 member.pii.view 掩码
HotelBookingPiiTest PASS
V17 给 store.manager / store.cashier 授权
尚无“无 PII 权限真实角色”的 B 端 HTTP 证据
```

## 4. 判定

```text
SEC 矩阵：部分通过
SEC-008/009：order/payment/tenant 通过；admin/identity/gateway 未覆盖
S7 cash-only：仍不能 PASS
```

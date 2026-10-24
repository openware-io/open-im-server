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

## 2. 失败 / 未通过

### SEC-008 离职成员旧 token 未失效（P0）

复现：

```text
1. B token（account 101, store 102）调用 /admin/hotel/bookings -> 200
2. tnt_membership id=1 status=DISABLED, authorization_version+1
3. 同一个旧 B token 再调用 -> 200
```

结论：

```text
TenantContextFilter 只校验 JWT 签名/issuer/audience
未校验当前 membership 状态与 authorization_version
旧 bearer token 在过期前仍可用
```

影响：

```text
离职员工、撤销角色后旧会话仍能访问，直到 token 自然过期
违反 SEC-008 / SEC-009
```

待修：

```text
请求级校验 account/membership 当前状态与授权版本
或缩短 token TTL + 服务端会话撤销绑定
```

### SEC-007 token 过期

```text
未构造过期 token 证据；依赖 JWT exp，未实测
```

### SEC-009 角色变更版本失效

```text
与 SEC-008 同根因，未通过
```

### SEC-010 PII

```text
代码已加 member.pii.view 掩码 + 单测
V17 给 manager/cashier 授权
尚无“无 PII 权限真实角色”的 B 端 HTTP 证据
```

## 3. 判定

```text
SEC 矩阵：未通过
SEC-008 / SEC-009 是 P0 安全缺口
```

S7 cash-only 仍不能 PASS，直到 SEC-008 修复并有真实回归。

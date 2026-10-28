# S6 旧模型冻结与平台角色授予

> 环境：Kind `open-im-local`，tenant 100
> 时间：2026-10-24

## 1. 旧 `iam_user_role` 冻结

V16：

```text
RENAME TABLE iam_user_role TO iam_user_role_archive
```

核对：

```text
SHOW TABLES LIKE 'iam_user_role%' -> iam_user_role_archive
```

代码：

```text
UserRoleRepositoryAdapter 只读 iam_role_binding
insert / revokeByAccountAndRole / updateScopeByAccountAndRole -> 410 LEGACY_ROLE_WRITE_DISABLED
UserRoleMapper / UserRolePo 已删除
```

## 2. 平台角色授予

V15 seed：

```text
platform.iam.role.manage
授予 platform.operator
```

入口：

```text
GET    /admin/iam/platform/role-bindings?accountId=
POST   /admin/iam/platform/role-bindings {accountId, roleId}
DELETE /admin/iam/platform/role-bindings/{id}
```

真实平台 token：

```text
permissions =
  platform.tenant.entitlement.manage,
  platform.iam.role.manage
```

授予：

```text
POST {"accountId":113,"roleId":1}
-> 200 {"id":2}
GET -> [2]
```

撤销：

```text
DELETE /2 -> 200
GET -> []
```

审计：

```text
iam.platform_role.assign  SUCCESS
iam.platform_role.revoke  SUCCESS
```

## 3. 测试

```text
PlatformRoleBindingApplicationServiceTest 3 PASS
tenant 176 PASS
identity 57 PASS
```

## 4. 未完成

```text
B createBooking 行为级失败回滚集成测试（S7 前）
S6 仲裁
```

## 5. 平台角色目标账号校验（2026-10-24）

新增 identity 内部账号查询 + tenant 校验：

```text
account_type = PLATFORM_OPERATOR
status = ACTIVE
无 ACTIVE 租户 membership
```

真实：

```text
POST {accountId:113, roleId:1}
-> 403 PLATFORM_OPERATOR_REQUIRED

POST {accountId:1, roleId:1}
（account_type=PLATFORM_OPERATOR, 无租户 membership）
-> 200 {"id":3}

DELETE /3 -> 200
```

说明：113 是 B 端 STAFF，已拒绝；此前误授的 binding 2 已 REVOKED。

旧脚本：

```text
backfill-account-type.sql -> iam_role_binding
migrate-iam-user-role.sql -> iam_user_role_archive
```

> 注：§2 的 account 113 -> id=2 是历史误授记录，已 REVOKED；现行规则见 §5，113 已 403。


# S2 迁移与冻结计划

> 状态：执行中（2026-10-23）
> 范围：membership / role_binding / account_type / cst_member.membership_id / iam_user_role 冻结。

## 1. 已完成

```text
tnt_membership / tnt_staff / iam_role_binding 表
Membership API
RoleBinding API（仅 STAFF membership）
跨租户 account 查询 @InterceptorIgnore + 显式 accountId
V3 删除重复 oauth 索引
```

## 2. account_type 收敛

状态：已完成代码、测试、V4、回填脚本；待业务库执行回填。

目标：

```text
idt_account.account_type = PLATFORM_OPERATOR | USER
员工/客户身份由 tnt_membership.membership_type 决定
```

顺序：

```text
1. 改 IdentityAccount：TYPE_USER，register/ofType/changeAccountType 归一化
2. 改调用方：admin StaffAccountApplicationService 传 USER
3. 改测试：EMPLOYEE/CUSTOMER 断言改 USER；平台运营保留 PLATFORM_OPERATOR
4. 跑 identity/admin/customer 测试
5. 执行 scripts/migration/backfill-account-type.sql
6. 校验只存在 PLATFORM_OPERATOR/USER
```

禁止：

```text
先改库后改代码
保留 EMPLOYEE/CUSTOMER 新写入
```

## 3. cst_member.membership_id 回填

脚本：

```text
scripts/migration/backfill-cst-member-membership.sql
```

规则：

```text
只匹配 account_id + tenant_id + CUSTOMER + ACTIVE
不自动创建 membership
未匹配行保持 NULL
account_id 与 membership.account_id 漂移必须报错
```

## 4. iam_user_role → iam_role_binding

```text
1. 由 iam_user_role.account_id + tenant_id 找到 STAFF membership
2. 插入 iam_role_binding：
   subject_type = MEMBERSHIP
   subject_id = membership.id
   role_id / scope_type / tenant_id / organization_id / store_id
   authorization_version = max(旧绑定版本, membership 版本)
3. 唯一键冲突跳过
4. 校验行数、角色、scope、跨租户
5. 代码切新表读写
6. 冻结 iam_user_role 写入
7. 保留只读窗口，最后删除旧路径
```

约束：

```text
找不到 STAFF membership 的 iam_user_role 行必须列报告，不能静默丢
PLATFORM 旧绑定不迁到 membership；平台角色仍绑定 ACCOUNT
```

## 5. 旧路径冻结

```text
先冻结写：IamAuthorizationApplicationService.assignUserRole 不再写 iam_user_role
再切读：权限快照改读 iam_role_binding
再删：旧表保留一个发布窗口，确认无读后删除
```

## 6. 门禁红灯

现有：

```text
flyway-conventions 9 条
tenant-sql RoleMapper 3 条
```

处理二选一，不默认忽略：

```text
A. 正式例外：ADR/负责人/到期/退出条件
B. 确认无历史库：重建基线后清零
```

禁止：

```text
改已执行迁移
report-only 绕过
改 validator 让红灯消失
```

## 7. S2 准出

```text
membership/role_binding API + 测试
account_type 代码/测试/回填完成
cst_member 回填脚本 + 校验
iam_user_role 迁移/冻结方案执行
四组租户上下文测试
空库+增量 Flyway
仲裁 PASS
```

## 8. 执行结果

```text
identity V4 Flyway：Successfully applied 1 migration, now at version v4
idt_account 数据核对：USER 2 条，account_type default USER
cst_member 回填：B-1 account_id=9 -> membership_id=1
```

剩余：

```text
iam_user_role 迁移/冻结
四组租户上下文集成测试
既有 flyway 9 条 + tenant-sql 3 条处理
S2 仲裁
```

### iam_user_role 迁移实测

```text
临时库 s1_role_migration
旧 STORE 绑定 account=9 tenant=100 role=7 store=20 version=3
STAFF membership id=1 authorization_version=4
迁移结果：
  subject_type=MEMBERSHIP, subject_id=1, scope=STORE
  tenant=100, organization=10, store=20, authorization_version=4
平台绑定 -> subject_type=ACCOUNT
无 STAFF membership 的旧行进入报告，不静默丢弃
```

## 9. 角色绑定切换结果

```text
/admin/iam/user-roles -> iam_role_binding（STAFF membership）
权限快照 SQL -> iam_role_binding + tnt_membership
撤销/scope -> membership 绑定
快照查询实测：tenant.store.manage 命中
tenant 151 PASS
```

待办：

```text
旧 IamAuthorizationApplicationService 角色分配方法标记 deprecated/删除
旧 iam_user_role 写入冻结验证
门禁红灯处理
S2 仲裁
```

# S1 Flyway 空库/增量验证记录

> 环境：Kind `open-im-local`，MySQL Pod `mysql-8b7bd4996-7cb6r`。
> 日期：2026-10-23。
> 工具：Flyway 11.14.1 + MySQL Connector/J 9.7.0，通过端口转发连接。
> 临时库为 `s1_flyway_*` / `s1_inc_flyway_*`，不影响业务库。

## 1. 空库

| 服务 | 数据库 | history 表 | 迁移数 | 最终版本 | 结果 |
| --- | --- | --- | --- | --- | --- |
| identity | `s1_flyway_identity` | `flyway_schema_history_identity` | 2 | v2 | PASS |
| tenant | `s1_flyway_tenant` | `flyway_schema_history_tenant` | 10 | v10 | PASS |
| customer | `s1_flyway_customer` | `flyway_schema_history_customer` | 2 | v2 | PASS |

Flyway 输出：

```text
identity: Successfully applied 2 migrations, now at version v2
tenant:   Successfully applied 10 migrations, now at version v10
customer: Successfully applied 2 migrations, now at version v2
```

history：

```text
identity v1 idt identity baseline success=1
identity v2 idt oauth app binding success=1
customer v1 cst customer baseline success=1
customer v2 cst member membership success=1
tenant v1..v10 all success=1
```

验证：

```text
identity: idt_account / idt_login_identity / idt_oauth_link / idt_profile_sync_record
identity: uk_idt_oauth_link_provider_app_account(provider, app_id, provider_account_id)
tenant: tnt_membership / tnt_staff / iam_role_binding
tenant: membership.organization_id、authorization_version；staff.display_name_cipher
customer: cst_member.membership_id + uk_cst_member_membership
```

## 2. 增量

| 服务 | 数据库 | 先到 | 再迁移 | 结果 |
| --- | --- | --- | --- | --- |
| identity | `s1_inc_flyway_identity` | v1 + 旧 link 行 | v2 | 旧行 `app_id=legacy`，数据保留 |
| tenant | `s1_inc_flyway_tenant` | v7 | v8/v9/v10 | 三表创建，既有表保留 |
| customer | `s1_inc_flyway_customer` | v1 + cst_member 行 | v2 | 旧行 `membership_id=NULL`，数据保留 |

Flyway 输出：

```text
identity: Successfully applied 1 migration, now at version v2
tenant:   Successfully applied 3 migrations, now at version v10
customer: Successfully applied 1 migration, now at version v2
```

数据核对：

```text
idt_oauth_link: app_id=legacy, provider_account_id=open_inc
cst_member: member_no=INC-1, membership_id=NULL
tenant history: 10 条 success=1
```

## 3. 复现

```powershell
kubectl port-forward -n open-im-local svc/mysql 13306:3306

java -cp <flyway+mysql+jackson+runner> FlywayMigrate `
  "jdbc:mysql://127.0.0.1:13306/s1_flyway_identity?useSSL=false&allowPublicKeyRetrieval=true" `
  root <password> `
  "<repo>/platform-services/identity/platform-identity-service/src/main/resources/db/migration" `
  flyway_schema_history_identity
```

增量：

```text
1. 先传 target=1/7/1 运行 Flyway
2. 插入旧结构数据
3. 再运行 Flyway 到 latest
```

## 4. 结论

S1 的 identity V2、tenant V8–V10、customer V2 均通过真实 Flyway 空库与增量迁移；旧数据保留，未出现版本冲突。

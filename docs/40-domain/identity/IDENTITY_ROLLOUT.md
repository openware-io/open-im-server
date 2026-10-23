# 身份与授权落地清单

> 对应 ADR-0015，状态 `accepted target model`。
> 原则：先补模型和边界，再切流量；旧 `iam_user_role`/`saa_admin_account` 路径冻结，不做长期双写。

## 1. 服务与迁移

| 服务 | 迁移 | 变更 |
| --- | --- | --- |
| `platform-identity-service` | 下一个 `V*` | `idt_oauth_link` 增加 `app_id/union_id`，唯一键改为 `(provider, app_id, provider_account_id)`；`idt_account.account_type` 收敛为 `PLATFORM_OPERATOR/USER` |
| `platform-tenant-service` | 下一个 `V*` | 新增 `tnt_membership`、`tnt_staff`、`iam_role_binding`、`tnt_entitlement`、`tnt_app_installation`、`iam_support_grant` |
| `platform-customer-service` | 下一个 `V*` | 新增 `cst_customer` |
| `im-user-service` | 下一个 `V*` | 新增 `im_open_app`、`im_user_app_grant` |

每个迁移单独版本、UTF-8、`datetime(3)`、审计字段、表注释、租户索引；不建跨库外键。

## 2. 代码结构

### identity-service

```text
domain/account/Account
domain/account/LoginIdentity
domain/account/OauthLink
domain/session/SessionContext
application/AccountApplicationService
application/OauthCallbackApplicationService
application/SessionApplicationService
api/controller/OauthController
api/controller/ContextController
infra/persistence/AccountMapper
infra/persistence/OauthLinkMapper
```

### tenant-service

```text
domain/membership/Membership
domain/membership/StaffProfile
domain/iam/RoleBinding
domain/entitlement/Entitlement
domain/app/AppInstallation
domain/support/SupportGrant
application/MembershipApplicationService
application/RoleAuthorizationApplicationService
application/EntitlementApplicationService
api/controller/MembershipController
api/controller/RoleBindingController
api/controller/EntitlementController
```

### customer-service

```text
domain/customer/Customer
application/CustomerApplicationService
infra/persistence/CustomerMapper
```

### IM

```text
im-user-service/domain/openplatform/OpenApp
im-user-service/domain/openplatform/UserAppGrant
im-user-service/api/controller/OauthController
```

依赖方向：

```text
identity -> tenant/customer internal API
tenant -> customer internal API (optional)
IM 不依赖 SaaS 业务
SaaS 不依赖 IM 内部表
```

## 3. API 实施顺序

```text
1. POST /identity/oauth/im/callback          appId + code + verifier
2. GET  /auth/contexts                       C/B 上下文分离
3. POST /auth/context/select                 发行 scope/audience 上下文
4. GET  /internal/iam/memberships            成员查询
5. POST /internal/iam/memberships            邀请员工 / 首登客户
6. GET  /internal/iam/memberships/{id}/permissions
7. POST /internal/iam/authorize              PDP 入口
8. GET  /internal/tenant/{id}/apps           服务入口安装/权益过滤
```

## 4. 应用注册

```text
saas-ktv-c        C  KTV
saas-ktv-b        B  KTV
saas-hotel-c      C  酒店
saas-hotel-b      B  酒店
```

每个应用：

```text
app_id
app_type
entry_url
scopes
redirect_uris
status
```

## 5. 数据迁移

```text
1. 建新表
2. 回填 idt_oauth_link.app_id = 现有 IM 应用 appId
3. 由 account + tenant 推导 tnt_membership
4. 由 account + tenant + store 推导 tnt_staff/cst_customer
5. iam_user_role -> iam_role_binding
   - subject_type = MEMBERSHIP
   - subject_id = tnt_membership.id
   - tenant_id/store_id 按原作用域
6. 校验行数、角色、权限、跨租户
7. 冻结旧写入
8. 删除旧路径
```

开发分支可以重置 IAM 数据；生产必须保留回填脚本与回滚点。

## 6. 测试与门禁

```text
Flyway 空库/增量
唯一键冲突
OAuth code 单次使用
PKCE/state/nonce
C token 调 B API
B token 调 C API
租户 A/B 隔离
门店 A/B 隔离
未绑定员工 403
未安装应用不可见
无权益 403
角色越权 403
平台代操作无 grant 403
代操作过期 403
权限快照版本失效
审计记录
```

## 7. 验收命令

```text
scripts/validate/validate-architecture-decisions.ps1
scripts/validate/validate-docs-impact.ps1
scripts/validate/validate-documentation-links.ps1
scripts/validate/validate-domain-map.ps1
mvn -pl <service> -am clean test
```

## 8. 完成定义

```text
C/B 应用分开注册
C/B cookie/audience 分开
membership 是唯一租户授权主体
iam_role_binding 取代 iam_user_role
应用安装/权益参与入口过滤
酒店门店 businessType=HOTEL
无跨库外键/无共享账号表
文档与 ADR 可达
```

## 9. 相关文档

- [身份与访问模型](IDENTITY_ACCESS_MODEL.md)
- [身份数据模型](IDENTITY_SCHEMA.md)
- [身份 API 契约](IDENTITY_API.md)
- [ADR-0015](../../20-architecture/adr/0015-identity-federation-tenant-iam.md)

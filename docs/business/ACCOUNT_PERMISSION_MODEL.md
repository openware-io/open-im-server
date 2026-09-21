# 账号权限体系（Account & Permission Model）

> 本文是产品级长期约定，沉淀账号、角色、权限、租户作用域与域边界。架构较复杂（多端多账号体系），任何涉及账号/权限的改动以本文为准。

## 1. 总览

平台分两个域、三类账号、两套角色体系，核心原则：

- **集团只管理、不参与业务**；业务数据全部在 SaaS 域。
- **全局只有一个超管账号 `admin`**，具备全权限，各子系统超管角色都归它。
- **权限可下放**：权限给角色，角色给指定的人（带作用域）。
- **租户管理员作用范围只能是租户级**，不得涉及 SaaS 平台级权限。

## 2. 域边界

| 域 | 服务 | 表 | 职责 |
| --- | --- | --- | --- |
| **集团管理域** | `group-services/idaas` | `group_account` / `group_org` / `idaas_client` | 集团统一账号、组织、登录/SSO。**只管理，不参与业务** |
| **SaaS 业务域** | `platform-services/*` + `common-services/*` | `idt_*` / `saa_*` / `tnt_*` / `iam_*` / `cst_*` / `ord_*` / `res_*` | 业务数据（会员/订单/钱包/资源/计价/经营作用域） |

集团 → SaaS 的唯一关联是 **SSO 映射**（`saa_admin_account.idaas_subject` → `group_account.id`）；业务表不引用集团 IDaaS 表。

## 3. 三类账号体系

| 账号 | 表 | 域 | 用途 |
| --- | --- | --- | --- |
| 集团账号 | `group_account` | 集团管理域 | 集团统一账号/组织/登录（`admin` id=1 为集团超管） |
| SaaS 平台账号 | `idt_account` | SaaS 业务域 | SaaS 平台身份（会员 OAuth 绑定、经营作用域 IAM 挂载载体） |
| SaaS 后台账号 | `saa_admin_account` | SaaS 业务域 | SaaS 后台 Web 登录（B 端①经营管理）；`idaas_subject` 关联集团账号做 SSO |

> 注意区分：`idt_*` 是 **SaaS 平台账号**（业务域），**不是 IDaaS**。IDaaS 是 `group_account`（集团管理域）。

## 4. 权限体系（一套，两个层次）

> 本质是**一套权限体系**：`saa_admin_account.role` 是账号的**级别**（粗粒度档位），`iam_role`/\`iam_role_permission\`/\`iam_user_role\` 是**权限角色**（细粒度），两者通过 `iam_user_role` 打通：账号(级别) → iam_user_role → iam_role → iam_role_permission → 权限。

### 4.1 账号级别（`saa_admin_account.role`）

- `SUPER_ADMIN`：平台超管（全权限，仅 `admin`）。
- `PLATFORM_ADMIN`：平台运营（跨租户运营，不含全权限）。
- `TENANT_ADMIN`：租户管理员（仅本租户，B 端①租户后台）。

### 4.2 SaaS 经营 IAM（`iam_role` + `iam_role_permission` + `iam_user_role`）

- 角色（`iam_role`）：`platform.operator`（平台运营）、`tenant.owner`（租户老板）、`store.manager`（店长）、`store.cashier`（收银员）、`store.finance`（财务）。
- 权限（`iam_permission`）：`tenant.tenant.manage`、`tenant.store.manage`、`iam.role.manage`、`ktv.session.operate`、`ktv.server.order`、`payment.collect`、`member.pii.view` 等。
- 作用域（`iam_user_role.scope_type`）：`TENANT` / `ORGANIZATION` / `STORE` / `SELF`。

## 5. 超管模型

- 全局唯一超管 = `admin`（集团 `group_account` id=1 + SaaS 后台 `saa_admin_account` `SUPER_ADMIN`，`idaas_subject=1`）。
- 全权限；各子系统超管角色都归 `admin`。
- **不得存在第二个超管账号**；租户/门店级管理员一律用 `TENANT_ADMIN` / `tenant.owner` 等。

## 6. 权限下放机制

- 权限 → 角色：`iam_role_permission`（任意角色可绑任意权限）。
- 角色 → 人：`iam_user_role`（把账号绑到角色，可限定租户/门店作用域）。
- 脱敏类权限（如 `member.pii.view`）默认脱敏，按上下文权限回显明文；可下放到指定角色/人。

## 7. 权限判定链路（端到端）

```text
登录(SaaS后台/IM) → 签发 token(subject=账号id)
  → 选上下文(Authorization: Bearer <accessToken> 校验签名取 accountId) → 查 iam_user_role 聚合可访问上下文
  → 权限快照(iam_role_permission 聚合) → 签 X-Tenant-Context token(含 permissions)
  → 业务服务 TenantContextFilter 解析 → TenantContextHolder
  → 业务层按 permissions 判定（无权限即拒绝/脱敏）
```

## 8. 表关系速查

- 集团账号：`group_account`（管理域）。
- 后台账号：`saa_admin_account`（`role` + `idaas_subject`）。
- 平台账号：`idt_account` + `idt_login_identity` + `idt_oauth_link`。
- 经营 IAM：`iam_role` → `iam_role_permission` ← `iam_permission`；`iam_user_role`（账号 + 作用域 + 角色）。

## 9. 待定/待办

1. **platform.operator 绑定**（已定案）：SUPER_ADMIN（后台角色）不覆盖 platform.operator（经营权限），故 admin 绑定 platform.operator——scope=PLATFORM，tenant_id=NULL，`iam_user_role` 已加 PLATFORM 作用域。
2. **账号 ID 对齐**（已定案）：经营 IAM 统一用 `idt_account.id`；`saa_admin_account` 新增 `platform_account_id` 关联 `idt_account.id`，登录/SSO 返回该字段，选上下文用 `platform_account_id` 作为 accountId（登录签发 JWT 的 subject）。
3. **IAM 管理界面**：SaaS 后台是否已有「角色/权限/用户角色」管理页，可让租户管理员自助下放权限，待核对。
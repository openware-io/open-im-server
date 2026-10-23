# 身份与访问数据模型

> 状态：`accepted target model`（2026-10-23）
> 决策：ADR-0015
> 说明：本文件是目标 DDL 基线，不是已执行迁移。实际迁移按各服务 Flyway 版本号落地，遵守 `23_DATABASE_MIGRATION_STANDARD.md`。

## 1. 服务与表归属

| 服务 | 表 | 说明 |
| --- | --- | --- |
| `platform-identity-service` | `idt_account`、`idt_login_identity`、`idt_oauth_link` | SaaS 账号与登录方式 |
| `platform-tenant-service` | `tnt_membership`、`tnt_staff`、`iam_role_binding` | S1 租户成员与角色绑定 |
| `platform-tenant-service` | `tnt_entitlement`、`tnt_app_installation`、`iam_support_grant` | S3/S4 目标；`tnt_entitlement` 需先与既有 `tnt_business_entitlement` 合并口径 |
| `platform-customer-service` | `cst_member`（增加 `membership_id`） | 复用现有客户主档，不建 `cst_customer` |
| `im-user-service` | `open_application`、`open_application_scope`、`open_user_authorization` | IM 开放平台应用与用户授权 |

跨服务只保存 ID，不建立跨库外键。

## 2. SaaS 账号模型

### 2.1 `idt_account`

已存在。目标字段语义调整：

```text
account_type = PLATFORM_OPERATOR | USER
```

S1 **不改** `account_type` 数据与代码，避免现有登录/测试中断；S2 同时改 `IdentityAccount`、测试和回填脚本后执行。

```sql
ALTER TABLE `idt_account`
  MODIFY COLUMN `account_type` varchar(32) NOT NULL DEFAULT 'USER'
    COMMENT '账号类型 PLATFORM_OPERATOR/USER';
```

### 2.2 `idt_oauth_link`

现有表缺少 OAuth client 维度。当前 IM openId 是全局 `im_<userId>`，`app_id` 仅记录 OAuth client；C/B 共享同一 SaaS account。

```sql
ALTER TABLE `idt_oauth_link`
  ADD COLUMN `app_id` varchar(64) NOT NULL DEFAULT 'legacy'
    COMMENT 'IM 开放平台应用ID' AFTER `provider`;

ALTER TABLE `idt_oauth_link`
  DROP INDEX `uk_idt_oauth_link_provider_account`,
  ADD UNIQUE KEY `uk_idt_oauth_link_provider_app_account`
    (`provider`, `app_id`, `provider_account_id`),
  ADD KEY `idx_idt_oauth_link_account_app` (`account_id`, `app_id`);
```

规则：

```text
provider_account_id = 当前 IM 全局 openId（im_<userId>）
app_id = OAuth client 维度，不改变 openId 全局唯一性
同一 (provider, app_id, openId) 不能绑定两个 SaaS account
已有 login_identity 时，新 app 只新增 oauth_link，不新建 SaaS account
idt_login_identity 仍按 (login_type, login_identifier) 唯一；同一个人 C/B 共用一个 SaaS account
解绑只改 status = REVOKED，不物理删除
```

## 3. 租户成员

### 3.1 `tnt_membership`

```sql
CREATE TABLE `tnt_membership` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '成员身份ID',
  `account_id` bigint unsigned NOT NULL COMMENT 'SaaS账号ID（idt_account.id，仅标识）',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `organization_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '组织ID；0表示未指定',
  `membership_type` varchar(16) NOT NULL COMMENT '成员类型 STAFF/CUSTOMER',
  `member_no` varchar(64) NOT NULL COMMENT '租户内成员编号',
  `authorization_version` int NOT NULL DEFAULT 1 COMMENT '授权版本；角色/权限变更递增',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/SUSPENDED/LEFT',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_membership_account_tenant_type` (`account_id`, `tenant_id`, `membership_type`),
  UNIQUE KEY `uk_tnt_membership_tenant_no` (`tenant_id`, `member_no`),
  KEY `idx_tnt_membership_tenant_type_status` (`tenant_id`, `membership_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户成员身份';
```

`organization_id` 是成员的主组织/默认组织，不参与唯一键；同一员工在多个组织任职由 `iam_role_binding.organization_id` 表达，不重复建 membership。

### 3.2 `tnt_staff`

```sql
CREATE TABLE `tnt_staff` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '员工档案ID',
  `membership_id` bigint unsigned NOT NULL COMMENT '成员身份ID（tnt_membership.id）',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `employee_no` varchar(64) NOT NULL COMMENT '员工编号',
  `display_name_cipher` varchar(255) NOT NULL COMMENT '员工姓名密文（PII，AES-256-GCM）',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/LEFT',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_staff_membership` (`membership_id`),
  UNIQUE KEY `uk_tnt_staff_tenant_employee_no` (`tenant_id`, `employee_no`),
  KEY `idx_tnt_staff_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户员工档案';
```

### 3.3 `cst_member` 增加 membership 关联

不新建 `cst_customer`；`cst_member` 已是客户主档，保留 `account_id`、`phone_cipher`、`phone_digest`。

```sql
ALTER TABLE `cst_member`
  ADD COLUMN `membership_id` bigint unsigned NULL
    COMMENT '租户成员身份ID（tnt_membership.id，仅标识）' AFTER `account_id`,
  ADD UNIQUE KEY `uk_cst_member_membership` (`membership_id`),
  ADD KEY `idx_cst_member_tenant_membership` (`tenant_id`, `membership_id`);
```

PII 约束：手机号/姓名继续使用 `phone_cipher`/`name_cipher` 加密列 + `phone_digest` 检索摘要，不新增明文列。

## 4. 角色绑定

```sql
CREATE TABLE `iam_role_binding` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '角色绑定ID',
  `subject_type` varchar(16) NOT NULL COMMENT '授权主体类型 ACCOUNT/MEMBERSHIP',
  `subject_id` bigint unsigned NOT NULL COMMENT '主体ID',
  `role_id` bigint unsigned NOT NULL COMMENT '角色ID（iam_role.id）',
  `scope_type` varchar(16) NOT NULL COMMENT '作用域 PLATFORM/TENANT/ORGANIZATION/STORE/SELF',
  `tenant_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '租户ID；平台为0',
  `organization_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '组织ID；非组织为0',
  `store_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '门店ID；非门店为0',
  `authorization_version` int NOT NULL DEFAULT 1 COMMENT '绑定授权版本；变更递增',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/REVOKED',
  `effective_from` datetime(3) NULL COMMENT '生效时间',
  `effective_to` datetime(3) NULL COMMENT '失效时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_role_binding_subject_role_scope`
    (`subject_type`, `subject_id`, `role_id`, `scope_type`, `tenant_id`, `organization_id`, `store_id`),
  KEY `idx_iam_role_binding_tenant_scope` (`tenant_id`, `scope_type`, `status`),
  KEY `idx_iam_role_binding_subject` (`subject_type`, `subject_id`, `status`),
  KEY `idx_iam_role_binding_org_scope` (`tenant_id`, `organization_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色绑定';
```

约束（应用层强制，DB 约束辅助）：

```text
PLATFORM:     subject_type=ACCOUNT, tenant_id=0, organization_id=0, store_id=0
TENANT:       subject_type=MEMBERSHIP, tenant_id>0, organization_id=0, store_id=0
ORGANIZATION: subject_type=MEMBERSHIP, tenant_id>0, organization_id>0, store_id=0
STORE:        subject_type=MEMBERSHIP, tenant_id>0, organization_id>=0, store_id>0
SELF:         subject_type=MEMBERSHIP, tenant_id>0, organization_id=0, store_id=0
```

## 5. 权益与应用安装（S3 目标，S1 不落）

```sql
CREATE TABLE `tnt_entitlement` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '权益ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `feature_code` varchar(64) NOT NULL COMMENT '权益编码 HOTEL/KTV',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/EXPIRED/DISABLED',
  `effective_from` datetime(3) NOT NULL COMMENT '生效时间',
  `effective_to` datetime(3) NULL COMMENT '失效时间',
  `source` varchar(32) NOT NULL DEFAULT 'MANUAL' COMMENT '来源 MANUAL/SUBSCRIPTION/TRIAL',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_entitlement_tenant_feature` (`tenant_id`, `feature_code`),
  KEY `idx_tnt_entitlement_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户业务权益';
```

```sql
CREATE TABLE `tnt_app_installation` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '应用安装ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `app_id` varchar(64) NOT NULL COMMENT 'IM 开放平台应用ID',
  `status` varchar(24) NOT NULL DEFAULT 'INSTALLED' COMMENT '状态 INSTALLED/DISABLED',
  `installed_at` datetime(3) NOT NULL COMMENT '安装时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_app_installation_tenant_app` (`tenant_id`, `app_id`),
  KEY `idx_tnt_app_installation_app_status` (`app_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户应用安装';
```

## 6. 平台代操作（S4 目标，S1 不落）

```sql
CREATE TABLE `iam_support_grant` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '代操作授权ID',
  `operator_account_id` bigint unsigned NOT NULL COMMENT '平台运营账号ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '目标租户ID',
  `scope` varchar(128) NOT NULL COMMENT '授权范围',
  `reason` varchar(255) NOT NULL COMMENT '代操作原因',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/REVOKED/EXPIRED',
  `expires_at` datetime(3) NOT NULL COMMENT '过期时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_iam_support_grant_operator_tenant` (`operator_account_id`, `tenant_id`, `status`),
  KEY `idx_iam_support_grant_expiry` (`status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台代操作授权';
```

## 7. IM 开放平台

IM 已有开放平台表，不重复建：

```text
open_application
open_application_scope
open_user_authorization
```

SaaS 只新增：

```text
tnt_app_installation
```

如果后续需要多 redirect URI 或结构化 scope，按新 ADR 扩展 `open_application_scope`，不在 SaaS 表里复制 IM 应用注册。

## 8. 迁移顺序

```text
identity-service:
  V2: idt_oauth_link 增加 app_id，重建唯一键
tenant-service:
  V8: tnt_membership
  V9: tnt_staff
  V10: iam_role_binding
customer-service:
  V2: cst_member 增加 membership_id + 唯一键（不新建 cst_customer）
im-user-service:
  已有 open_application/open_application_scope/open_user_authorization
  不新增迁移
```

S3 再落 `tnt_entitlement` / `tnt_app_installation`，S4 落 `iam_support_grant`；`tnt_entitlement` 先与既有 `tnt_business_entitlement` 统一口径。


每个版本一个独立迁移；禁止把多张表塞进一个不可审查脚本（除非同域同批评审通过）。

## 9. 门禁

```text
Flyway 命名/编码/不可回改
tenant_id/store_id 索引
无跨库外键
唯一键包含租户
PII 字段不落明文
角色绑定 scope 校验
跨租户测试
C/B audience 测试
```

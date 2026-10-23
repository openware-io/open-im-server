# 身份与访问数据模型

> 状态：`accepted target model`（2026-10-23）
> 决策：ADR-0015
> 说明：本文件是目标 DDL 基线，不是已执行迁移。实际迁移按各服务 Flyway 版本号落地，遵守 `23_DATABASE_MIGRATION_STANDARD.md`。

## 1. 服务与表归属

| 服务 | 表 | 说明 |
| --- | --- | --- |
| `platform-identity-service` | `idt_account`、`idt_login_identity`、`idt_oauth_link` | SaaS 账号与登录方式 |
| `platform-tenant-service` | `tnt_membership`、`tnt_staff`、`iam_role_binding`、`tnt_entitlement`、`tnt_app_installation`、`iam_support_grant` | 租户成员、角色绑定、权益、应用安装、代操作 |
| `platform-customer-service` | `cst_customer` | 客户档案 |
| `im-user-service` | `im_open_app`、`im_user_app_grant` | IM 开放平台应用与用户授权 |

跨服务只保存 ID，不建立跨库外键。

## 2. SaaS 账号模型

### 2.1 `idt_account`

已存在。目标字段语义调整：

```text
account_type = PLATFORM_OPERATOR | USER
```

不再用 `EMPLOYEE/CUSTOMER` 作为全局唯一类型。  
员工/客户身份由 `tnt_membership.membership_type` 决定。

```sql
ALTER TABLE `idt_account`
  MODIFY COLUMN `account_type` varchar(32) NOT NULL DEFAULT 'USER'
    COMMENT '账号类型 PLATFORM_OPERATOR/USER';
```

### 2.2 `idt_oauth_link`

现有表缺少应用维度，OpenID 是应用级的。

```sql
ALTER TABLE `idt_oauth_link`
  ADD COLUMN `app_id` varchar(64) NOT NULL DEFAULT 'legacy'
    COMMENT 'IM 开放平台应用ID' AFTER `provider`,
  ADD COLUMN `union_id` varchar(128) NULL
    COMMENT 'IM UnionID（同一开放平台主体）' AFTER `provider_account_id`;

ALTER TABLE `idt_oauth_link`
  DROP INDEX `uk_idt_oauth_link_provider_account`,
  ADD UNIQUE KEY `uk_idt_oauth_link_provider_app_account`
    (`provider`, `app_id`, `provider_account_id`),
  ADD KEY `idx_idt_oauth_link_account_app` (`account_id`, `app_id`);
```

规则：

```text
provider_account_id = 该 app_id 下的 openId
同一 openId 不能绑定两个 SaaS account
解绑只改 status = REVOKED，不物理删除
```

## 3. 租户成员

### 3.1 `tnt_membership`

```sql
CREATE TABLE `tnt_membership` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '成员身份ID',
  `account_id` bigint unsigned NOT NULL COMMENT 'SaaS账号ID（idt_account.id，仅标识）',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `membership_type` varchar(16) NOT NULL COMMENT '成员类型 STAFF/CUSTOMER',
  `member_no` varchar(64) NOT NULL COMMENT '租户内成员编号',
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

### 3.2 `tnt_staff`

```sql
CREATE TABLE `tnt_staff` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '员工档案ID',
  `membership_id` bigint unsigned NOT NULL COMMENT '成员身份ID（tnt_membership.id）',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `employee_no` varchar(64) NOT NULL COMMENT '员工编号',
  `display_name` varchar(128) NOT NULL COMMENT '员工姓名',
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

### 3.3 `cst_customer`

```sql
CREATE TABLE `cst_customer` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '客户档案ID',
  `membership_id` bigint unsigned NOT NULL COMMENT '成员身份ID（tnt_membership.id）',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `customer_no` varchar(64) NOT NULL COMMENT '客户编号',
  `display_name` varchar(128) NULL COMMENT '客户名称',
  `phone_hash` varchar(128) NULL COMMENT '手机号哈希（PII）',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/DISABLED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_customer_membership` (`membership_id`),
  UNIQUE KEY `uk_cst_customer_tenant_no` (`tenant_id`, `customer_no`),
  KEY `idx_cst_customer_tenant_phone` (`tenant_id`, `phone_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户客户档案';
```

## 4. 角色绑定

```sql
CREATE TABLE `iam_role_binding` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '角色绑定ID',
  `subject_type` varchar(16) NOT NULL COMMENT '授权主体类型 ACCOUNT/MEMBERSHIP',
  `subject_id` bigint unsigned NOT NULL COMMENT '主体ID',
  `role_id` bigint unsigned NOT NULL COMMENT '角色ID（iam_role.id）',
  `scope_type` varchar(16) NOT NULL COMMENT '作用域 PLATFORM/TENANT/STORE',
  `tenant_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '租户ID；平台为0',
  `store_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '门店ID；非门店为0',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/REVOKED',
  `effective_from` datetime(3) NULL COMMENT '生效时间',
  `effective_to` datetime(3) NULL COMMENT '失效时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_role_binding_subject_role_scope`
    (`subject_type`, `subject_id`, `role_id`, `scope_type`, `tenant_id`, `store_id`),
  KEY `idx_iam_role_binding_tenant_scope` (`tenant_id`, `scope_type`, `status`),
  KEY `idx_iam_role_binding_subject` (`subject_type`, `subject_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色绑定';
```

约束（应用层强制，DB 约束辅助）：

```text
PLATFORM: subject_type=ACCOUNT, tenant_id=0, store_id=0
TENANT:   subject_type=MEMBERSHIP, tenant_id>0, store_id=0
STORE:    subject_type=MEMBERSHIP, tenant_id>0, store_id>0
```

## 5. 权益与应用安装

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

## 6. 平台代操作

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

```sql
CREATE TABLE `im_open_app` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '应用ID',
  `app_id` varchar(64) NOT NULL COMMENT '应用唯一编码',
  `app_type` varchar(16) NOT NULL COMMENT '应用类型 C/B',
  `name` varchar(128) NOT NULL COMMENT '应用名称',
  `entry_url` varchar(512) NOT NULL COMMENT 'SaaS 应用入口',
  `scopes` varchar(512) NOT NULL COMMENT '允许的授权范围，逗号分隔',
  `redirect_uris` varchar(1024) NOT NULL COMMENT '允许的回调地址，逗号分隔',
  `status` varchar(24) NOT NULL DEFAULT 'APPROVED' COMMENT '状态 APPROVED/SUSPENDED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_im_open_app_app_id` (`app_id`),
  KEY `idx_im_open_app_type_status` (`app_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IM开放平台应用';
```

```sql
CREATE TABLE `im_user_app_grant` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '用户应用授权ID',
  `im_user_id` bigint unsigned NOT NULL COMMENT 'IM用户ID',
  `app_id` varchar(64) NOT NULL COMMENT '应用ID',
  `open_id` varchar(128) NOT NULL COMMENT '应用内用户标识',
  `scopes` varchar(512) NOT NULL COMMENT '已授权范围',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/REVOKED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_im_user_app_grant_app_open_id` (`app_id`, `open_id`),
  UNIQUE KEY `uk_im_user_app_grant_user_app` (`im_user_id`, `app_id`),
  KEY `idx_im_user_app_grant_user_status` (`im_user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IM用户应用授权';
```

## 8. 迁移顺序

```text
identity-service:
  Vn: idt_oauth_link 增加 app_id/union_id，重建唯一键
tenant-service:
  Vn: tnt_membership
  Vn: tnt_staff
  Vn: iam_role_binding
  Vn: tnt_entitlement
  Vn: tnt_app_installation
  Vn: iam_support_grant
customer-service:
  Vn: cst_customer
im-user-service:
  Vn: im_open_app
  Vn: im_user_app_grant
```

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

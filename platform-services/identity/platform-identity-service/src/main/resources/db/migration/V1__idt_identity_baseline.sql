-- SaaS 平台账号（identity）基线
-- 基线重建（批次 2）：合并原 V1~V5 全部变更，本文件单独产出最终结构与数据状态。
--   * 每表补齐 4 个审计字段（created_by / created_at / updated_by / updated_at）；
--   * 索引名统一为 <前缀>_<完整表名>_<语义>（表名不缩写）；
--   * 并入 idt_profile_sync_record.event_id（同步事件消费幂等键）；
--   * 并入 idt_account.account_type（统一账号主体类型）；
--   * 原 V3 / V4 种子数据内联到本文件末尾，保持幂等。
-- 说明：表名保持单数，沿用已登记前缀 idt_。

CREATE TABLE `idt_account` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '账号ID',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '账号状态 ACTIVE/SUSPENDED/CLOSED',
  `account_type` varchar(32) NOT NULL DEFAULT 'CUSTOMER' COMMENT '账号类型 EMPLOYEE/CUSTOMER/PLATFORM_OPERATOR',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SaaS平台账号';

CREATE TABLE `idt_login_identity` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '登录标识ID',
  `account_id` bigint unsigned NOT NULL COMMENT '账号ID',
  `login_type` varchar(16) NOT NULL COMMENT '登录类型 PHONE/EMAIL/IM',
  `login_identifier` varchar(128) NOT NULL COMMENT '登录标识（手机号/邮箱/IM账号，规范化）',
  `credential` varchar(255) NULL COMMENT '凭据（密码哈希，第三方为空）',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idt_login_identity_type_identifier` (`login_type`, `login_identifier`),
  KEY `idx_idt_login_identity_account` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SaaS账号登录标识';

CREATE TABLE `idt_oauth_link` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'OAuth绑定ID',
  `account_id` bigint unsigned NOT NULL COMMENT '账号ID',
  `provider` varchar(16) NOT NULL COMMENT '第三方提供方 IM',
  `provider_account_id` varchar(128) NOT NULL COMMENT '第三方账号ID',
  `scope` varchar(255) NULL COMMENT '授权范围',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idt_oauth_link_provider_account` (`provider`, `provider_account_id`),
  KEY `idx_idt_oauth_link_account` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SaaS账号OAuth绑定';

CREATE TABLE `idt_profile_sync_record` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '同步记录ID',
  `event_id` varchar(64) NULL COMMENT '事件ID(消费幂等键，bind 拉取为空)',
  `account_id` bigint unsigned NOT NULL COMMENT '账号ID',
  `provider` varchar(16) NOT NULL COMMENT '来源 IM',
  `profile_json` json NULL COMMENT '授权范围内资料快照',
  `occurred_at` datetime(3) NOT NULL COMMENT '同步时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idt_profile_sync_record_event` (`event_id`),
  KEY `idx_idt_profile_sync_record_account` (`account_id`, `occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SaaS账号资料同步记录';

-- 资料同步事件消费幂等：event_id 唯一键兜底去重（bind 拉取路径 event_id 为空，多个 NULL 不冲突）
-- 账号类型回填在服务启动后由 scripts/migration/backfill-account-type.sql 显式执行
-- （依赖 admin/tenant 域表，避免跨域 Flyway 顺序依赖）。

-- A380 租户管理员的 SaaS 平台账号（idt_account），固定 id=100 与租户对齐
-- 说明：cons_admin_account（a380-admin, TENANT_ADMIN）为后台登录账号；本账号为经营作用域(iAM)挂载载体。
INSERT INTO `idt_account` (`id`, `status`, `created_at`, `updated_at`)
VALUES (100, 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE `updated_at` = `updated_at`;

-- 集团超管 admin 的 SaaS 平台账号（idt_account.id=1，与集团 admin / cons_admin_account 对齐）
-- 用途：经营 IAM（iam_user_role.account_id）挂载 platform.operator 平台级角色
INSERT INTO `idt_account` (`id`, `status`, `created_at`, `updated_at`)
VALUES (1, 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE `updated_at` = `updated_at`;
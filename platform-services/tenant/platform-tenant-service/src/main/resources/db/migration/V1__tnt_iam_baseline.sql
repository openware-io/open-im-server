-- 租户与 IAM 基线
CREATE TABLE `tnt_tenant` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '租户ID',
  `tenant_code` varchar(32) NOT NULL COMMENT '租户编码',
  `name` varchar(128) NOT NULL COMMENT '租户名称',
  `status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT '状态 PENDING/ACTIVE/SUSPENDED/CLOSED',
  `default_locale` varchar(16) NULL COMMENT '默认语言',
  `default_timezone` varchar(64) NULL COMMENT '默认IANA时区',
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_tenant_code` (`tenant_code`),
  KEY `idx_tnt_tenant_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户';

CREATE TABLE `tnt_organization` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `tenant_id` bigint unsigned NOT NULL,
  `code` varchar(64) NOT NULL, `name` varchar(128) NOT NULL, `status` varchar(24) NOT NULL DEFAULT 'ACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_tnt_organization_tenant_code` (`tenant_id`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='组织';

CREATE TABLE `tnt_store` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `tenant_id` bigint unsigned NOT NULL, `organization_id` bigint unsigned NOT NULL,
  `code` varchar(64) NOT NULL, `name` varchar(128) NOT NULL,
  `business_type` varchar(32) NOT NULL COMMENT '业态 KTV/HOTEL/SPA/MASSAGE/RETAIL',
  `country_code` char(2) NULL, `region_code` varchar(32) NULL, `timezone` varchar(64) NULL,
  `default_currency` char(3) NULL, `locale` varchar(16) NULL, `tax_profile_id` bigint unsigned NULL,
  `business_day_cutoff` time NULL, `status` varchar(24) NOT NULL DEFAULT 'ACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_tnt_store_tenant_code` (`tenant_id`, `code`),
  KEY `idx_tnt_store_org_status` (`tenant_id`, `organization_id`, `status`),
  KEY `idx_tnt_store_business_status` (`tenant_id`, `business_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='门店';

CREATE TABLE `tnt_tenant_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `tenant_id` bigint unsigned NOT NULL,
  `config_key` varchar(64) NOT NULL COMMENT '配置键 如 wallet_brand_name',
  `config_value` varchar(255) NOT NULL COMMENT '配置值',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE', `version` int NOT NULL DEFAULT 0,
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_tnt_tenant_config_tenant_key` (`tenant_id`, `config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户配置';

CREATE TABLE `tnt_business_type` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `code` varchar(32) NOT NULL COMMENT '业态编码',
  `name` varchar(64) NOT NULL COMMENT '业态名称', `status` varchar(24) NOT NULL DEFAULT 'ACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_tnt_business_type_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业态枚举';

CREATE TABLE `iam_permission` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `code` varchar(128) NOT NULL COMMENT '权限码 module.resource.action',
  `module` varchar(64) NOT NULL, `resource` varchar(64) NOT NULL, `action` varchar(64) NOT NULL,
  `description` varchar(255) NULL, `status` varchar(24) NOT NULL DEFAULT 'ACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_iam_permission_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权限';

CREATE TABLE `iam_role` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `tenant_id` bigint unsigned NULL COMMENT '平台角色为空',
  `code` varchar(64) NOT NULL, `name` varchar(128) NOT NULL,
  `role_type` varchar(16) NOT NULL COMMENT 'sdk/PRESET/TENANT',
  `copy_from_role_id` bigint unsigned NULL, `status` varchar(24) NOT NULL DEFAULT 'ACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_iam_role_tenant_code` (`tenant_id`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色';

CREATE TABLE `iam_role_permission` (
  `role_id` bigint unsigned NOT NULL, `permission_id` bigint unsigned NOT NULL,
  PRIMARY KEY (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色权限';

CREATE TABLE `iam_user_role` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `account_id` bigint unsigned NOT NULL,
  `tenant_id` bigint unsigned NOT NULL, `organization_id` bigint unsigned NULL, `store_id` bigint unsigned NULL,
  `role_id` bigint unsigned NOT NULL, `scope_type` varchar(16) NOT NULL COMMENT 'TENANT/ORGANIZATION/STORE/SELF',
  `effective_from` datetime(3) NULL, `effective_to` datetime(3) NULL,
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE', `authorization_version` int NOT NULL DEFAULT 0,
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_iam_user_role_account` (`account_id`, `status`),
  KEY `idx_iam_user_role_scope` (`tenant_id`, `organization_id`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色分配';

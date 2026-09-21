-- SaaS 平台账号（identity）基线
CREATE TABLE `idt_account` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '账号ID',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '账号状态 ACTIVE/SUSPENDED/CLOSED',
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
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
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
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idt_oauth_link_provider_account` (`provider`, `provider_account_id`),
  KEY `idx_idt_oauth_link_account` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SaaS账号OAuth绑定';

CREATE TABLE `idt_profile_sync_record` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '同步记录ID',
  `account_id` bigint unsigned NOT NULL COMMENT '账号ID',
  `provider` varchar(16) NOT NULL COMMENT '来源 IM',
  `profile_json` json NULL COMMENT '授权范围内资料快照',
  `occurred_at` datetime(3) NOT NULL COMMENT '同步时间',
  PRIMARY KEY (`id`),
  KEY `idx_idt_profile_sync_account` (`account_id`, `occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SaaS账号资料同步记录';

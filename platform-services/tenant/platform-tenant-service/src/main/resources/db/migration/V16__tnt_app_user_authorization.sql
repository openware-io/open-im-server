-- Per-application user grant. This is the user-level half of enterprise installation authorization.
CREATE TABLE `tnt_app_user_authorization`
(
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `app_id` varchar(128) NOT NULL COMMENT '应用标识',
  `account_id` bigint unsigned NOT NULL COMMENT 'SaaS账号标识',
  `tenant_id` bigint unsigned NOT NULL COMMENT '企业租户标识',
  `scope` varchar(1024) NOT NULL COMMENT '用户批准的API范围',
  `status` varchar(32) NOT NULL COMMENT 'ACTIVE or REVOKED',
  `authorization_version` int NOT NULL DEFAULT '1' COMMENT '授权版本',
  `authorized_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '授权时间',
  `revoked_at` datetime(3) NULL COMMENT '撤销时间',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_app_user_authorization_app_account` (`app_id`, `account_id`),
  KEY `idx_tnt_app_user_authorization_tenant` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应用用户授权';

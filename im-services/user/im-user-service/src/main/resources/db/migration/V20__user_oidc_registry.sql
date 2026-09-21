-- OIDC v1 registry and consent facts. Legacy open_application remains the compatibility registry.
CREATE TABLE `user_oidc_client`
(
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `client_id` varchar(128) NOT NULL COMMENT 'OIDC client identifier',
  `client_name` varchar(128) NOT NULL COMMENT '展示名称',
  `client_type` varchar(32) NOT NULL COMMENT 'PUBLIC or CONFIDENTIAL',
  `token_endpoint_auth_method` varchar(64) NOT NULL COMMENT 'none or client_secret_basic',
  `status` varchar(32) NOT NULL COMMENT 'ACTIVE or REVOKED',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_oidc_client_client_id` (`client_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OIDC客户端注册';

CREATE TABLE `user_oidc_redirect_uri`
(
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `client_id` varchar(128) NOT NULL COMMENT 'OIDC client identifier',
  `redirect_uri` varchar(512) NOT NULL COMMENT '精确回调地址',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_oidc_redirect_client_uri` (`client_id`, `redirect_uri`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OIDC回调白名单';

CREATE TABLE `user_oidc_scope`
(
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `client_id` varchar(128) NOT NULL COMMENT 'OIDC client identifier',
  `scope` varchar(128) NOT NULL COMMENT '授权范围',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_oidc_scope_client_scope` (`client_id`, `scope`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OIDC客户端授权范围';

CREATE TABLE `user_oidc_consent`
(
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `client_id` varchar(128) NOT NULL COMMENT 'OIDC client identifier',
  `user_id` bigint unsigned NOT NULL COMMENT '用户标识',
  `scope` varchar(1024) NOT NULL COMMENT '最终批准范围',
  `status` varchar(32) NOT NULL COMMENT 'ACTIVE or REVOKED',
  `authorized_at` datetime(3) NOT NULL COMMENT '授权时间',
  `revoked_at` datetime(3) NULL COMMENT '撤销时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_oidc_consent_client_user` (`client_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OIDC用户同意记录';

CREATE TABLE `user_oidc_token_family`
(
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `family_id` varchar(128) NOT NULL COMMENT 'refresh token family',
  `client_id` varchar(128) NOT NULL COMMENT 'OIDC client identifier',
  `user_id` bigint unsigned NOT NULL COMMENT '用户标识',
  `status` varchar(32) NOT NULL COMMENT 'ACTIVE or REVOKED',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `revoked_at` datetime(3) NULL COMMENT '撤销时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_oidc_token_family_family` (`family_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OIDC刷新令牌族';

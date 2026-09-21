-- 集团 IDaaS 基线：集团账号 + 组织 + 接入方三张表
CREATE TABLE `group_account` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '账号ID',
  `username` varchar(64) NOT NULL COMMENT '用户名（唯一）',
  `password` varchar(100) NOT NULL COMMENT 'BCrypt 密码哈希',
  `display_name` varchar(64) NULL COMMENT '显示名',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED' COMMENT '状态 ENABLED/DISABLED',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_account_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='集团管理员账号';

CREATE TABLE `group_org` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '组织ID',
  `name` varchar(64) NOT NULL COMMENT '组织名称',
  `code` varchar(64) NOT NULL COMMENT '组织编码',
  `parent_id` bigint unsigned NULL COMMENT '父组织ID（当前仅一级，暂不使用）',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED' COMMENT '状态 ENABLED/DISABLED',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_org_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='集团组织';

CREATE TABLE `idaas_client` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '接入方ID',
  `name` varchar(64) NOT NULL COMMENT '接入方名称',
  `client_id` varchar(64) NOT NULL COMMENT '客户端ID（唯一）',
  `client_secret` varchar(128) NOT NULL COMMENT '客户端密钥',
  `redirect_uris` varchar(512) NULL COMMENT '逗号分隔的重定向 URI',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED' COMMENT '状态 ENABLED/DISABLED',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idaas_client_client_id` (`client_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IDaaS 接入方';

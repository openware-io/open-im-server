-- SaaS 后台管理员账号（platform-admin 域，saa_ 前缀）
-- 口径依据：SAAS 后台账号体系 item 1（管理员表 + IDaaS 映射 + 后端鉴权）

CREATE TABLE `saa_admin_account` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '管理员账号ID',
  `username` varchar(64) NOT NULL COMMENT '登录用户名',
  `password_hash` varchar(100) NULL COMMENT 'BCrypt 密码哈希（SSO 映射账号可为空）',
  `display_name` varchar(128) NOT NULL COMMENT '展示名',
  `idaas_subject` varchar(128) NULL COMMENT '集团 IDaaS group_account.id（SSO 映射键）',
  `role` varchar(32) NOT NULL DEFAULT 'SUPER_ADMIN' COMMENT 'SUPER_ADMIN/PLATFORM_ADMIN/TENANT_ADMIN',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/SUSPENDED/CLOSED',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_saa_admin_account_username` (`username`),
  KEY `idx_saa_admin_account_idaas_subject` (`idaas_subject`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SaaS后台管理员账号';

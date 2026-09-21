-- 种子数据：默认管理员 admin/e8280ac0d25d4bc0a1e1、默认组织「集团」、两个接入方（im-admin / saas-admin）
INSERT IGNORE INTO `group_account` (`username`, `password`, `display_name`, `status`, `created_at`, `updated_at`)
VALUES ('admin', '$2b$10$uj5EtFLiaUGBRWv5gbSFfeC85AT5OTIJuTzYUZ7PUD/StOSsCeXtS', '集团管理员', 'ENABLED', NOW(3), NOW(3));

INSERT IGNORE INTO `group_org` (`name`, `code`, `parent_id`, `status`)
VALUES ('集团', 'group', NULL, 'ENABLED');

INSERT IGNORE INTO `idaas_client` (`name`, `client_id`, `client_secret`, `redirect_uris`, `status`, `created_at`, `updated_at`)
VALUES ('IM 后台', 'im-admin', 'im-admin-secret-placeholder-7f3a9c1d', 'http://localhost:3000/oauth/callback', 'ENABLED', NOW(3), NOW(3));

INSERT IGNORE INTO `idaas_client` (`name`, `client_id`, `client_secret`, `redirect_uris`, `status`, `created_at`, `updated_at`)
VALUES ('SaaS 后台', 'saas-admin', 'saas-admin-secret-placeholder-9b2e4f7a', 'http://localhost:4100/oauth/callback', 'ENABLED', NOW(3), NOW(3));

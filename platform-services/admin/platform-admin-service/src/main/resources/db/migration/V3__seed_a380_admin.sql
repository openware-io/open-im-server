-- A380 租户管理员（TENANT_ADMIN）：a380-admin / e8280ac0d25d4bc0a1e1
INSERT INTO saa_admin_account (username, password_hash, display_name, idaas_subject, role, status, created_at, updated_at)
VALUES ('a380-admin', '$2b$10$uj5EtFLiaUGBRWv5gbSFfeC85AT5OTIJuTzYUZ7PUD/StOSsCeXtS', 'A380 管理员', NULL, 'TENANT_ADMIN', 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE updated_at = updated_at;

-- SaaS 后台管理员种子账号：admin / e8280ac0d25d4bc0a1e1（BCrypt 真实哈希）
-- idaas_subject=1 对应集团 IDaaS group_account 的 admin

INSERT INTO `saa_admin_account`
  (`username`, `password_hash`, `display_name`, `idaas_subject`, `role`, `status`, `created_at`, `updated_at`)
VALUES
  ('admin', '$2b$10$uj5EtFLiaUGBRWv5gbSFfeC85AT5OTIJuTzYUZ7PUD/StOSsCeXtS', '管理员', '1', 'SUPER_ADMIN', 'ACTIVE', NOW(3), NOW(3));

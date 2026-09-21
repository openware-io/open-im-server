-- SaaS 后台账号关联 SaaS 平台账号（idt_account.id），用于经营 IAM 上下文（iam_user_role.account_id）
ALTER TABLE saa_admin_account
  ADD COLUMN platform_account_id bigint unsigned NULL COMMENT '关联 SaaS 平台账号 idt_account.id' AFTER idaas_subject;

-- 回填：admin → idt_account.id=1；a380-admin → idt_account.id=100
UPDATE saa_admin_account SET platform_account_id = 1   WHERE username = 'admin';
UPDATE saa_admin_account SET platform_account_id = 100 WHERE username = 'a380-admin';

-- 集团超管 admin 的 SaaS 平台账号（idt_account.id=1，与集团 admin / saa_admin_account 对齐）
-- 用途：经营 IAM（iam_user_role.account_id）挂载 platform.operator 平台级角色
INSERT INTO idt_account (id, status, created_at, updated_at)
VALUES (1, 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE updated_at = updated_at;

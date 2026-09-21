-- A380 租户管理员的 SaaS 平台账号（idt_account），固定 id=100 与租户对齐
-- 说明：saa_admin_account（a380-admin, TENANT_ADMIN）为后台登录账号；本账号为经营作用域(iAM)挂载载体。
INSERT INTO idt_account (id, status, created_at, updated_at)
VALUES (100, 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE updated_at = updated_at;

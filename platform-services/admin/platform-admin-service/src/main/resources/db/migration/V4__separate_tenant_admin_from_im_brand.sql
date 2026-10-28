-- Tenant administrator belongs to SaaS, not to the IM product brand.
UPDATE cons_admin_account
SET username = 'tenant-admin', display_name = '租户管理员'
WHERE username = 'openchat-admin';

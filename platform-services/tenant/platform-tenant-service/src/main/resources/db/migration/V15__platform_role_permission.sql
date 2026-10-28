-- S6 平台角色授予权限。
INSERT INTO `iam_permission`
  (`code`, `module`, `resource`, `action`, `description`, `status`, `scope_level`,
   `domain_code`, `grantable_levels`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('platform.iam.role.manage', 'platform', 'iam.role', 'manage',
   '平台角色授予/撤销', 'ACTIVE', 'PLATFORM', 'core', 'PLATFORM', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE `description` = VALUES(`description`), `status` = 'ACTIVE',
  `scope_level` = 'PLATFORM', `updated_at` = NOW(3);

INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT r.id, p.id, 0, NOW(3), 0, NOW(3)
FROM `iam_role` r
JOIN `iam_permission` p ON p.code = 'platform.iam.role.manage'
WHERE r.code = 'platform.operator' AND r.tenant_id IS NULL AND r.status = 'ACTIVE'
  AND NOT EXISTS (
    SELECT 1 FROM `iam_role_permission` rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

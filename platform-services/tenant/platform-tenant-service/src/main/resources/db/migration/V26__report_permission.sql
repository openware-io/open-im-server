-- 报表访问权限：报表此前只有菜单入口，没有接口权限码；补上后所有报表端点默认拒绝。
INSERT INTO `iam_permission`
  (`code`, `module`, `resource`, `action`, `description`, `status`, `scope_level`, `domain_code`, `grantable_levels`, `menu_code`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('report.view', 'report', 'report', 'view', '查看门店/租户经营报表', 'ACTIVE', 'TENANT', 'core', 'TENANT,STORE', 'report', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE
  `description` = VALUES(`description`),
  `scope_level` = VALUES(`scope_level`),
  `grantable_levels` = VALUES(`grantable_levels`),
  `menu_code` = VALUES(`menu_code`),
  `updated_at` = VALUES(`updated_at`);

UPDATE `iam_menu`
SET `required_permission` = 'report.view', `updated_at` = NOW(3)
WHERE `code` = 'report';

INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT r.`id`, p.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` r JOIN `iam_permission` p ON p.`code` = 'report.view'
WHERE r.`code` IN ('tenant.owner', 'store.manager', 'store.finance', 'platform.operator')
ON DUPLICATE KEY UPDATE `updated_at` = VALUES(`updated_at`);

-- 动作级权限：页面权限之外，业务动作单独授权。
-- 权限目录是平台级；角色权限仍按 iam_role_permission 授权。
INSERT INTO `iam_permission`
  (`code`, `module`, `resource`, `action`, `description`, `status`, `scope_level`, `domain_code`, `grantable_levels`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('tenant.business.manage', 'tenant', 'business', 'manage', '开通/停用租户业态', 'ACTIVE', 'TENANT', 'core', 'TENANT', 0, NOW(3), 0, NOW(3)),
  ('hotel.booking.view', 'hotel', 'booking', 'view', '查看酒店预订', 'ACTIVE', 'STORE', 'hotel', 'STORE', 0, NOW(3), 0, NOW(3)),
  ('hotel.booking.create', 'hotel', 'booking', 'create', '创建酒店预订', 'ACTIVE', 'STORE', 'hotel', 'STORE', 0, NOW(3), 0, NOW(3)),
  ('hotel.checkin', 'hotel', 'stay', 'checkin', '办理入住', 'ACTIVE', 'STORE', 'hotel', 'STORE', 0, NOW(3), 0, NOW(3)),
  ('hotel.checkout', 'hotel', 'stay', 'checkout', '结账退房', 'ACTIVE', 'STORE', 'hotel', 'STORE', 0, NOW(3), 0, NOW(3)),
  ('hotel.rate.update', 'hotel', 'rate', 'update', '维护房价', 'ACTIVE', 'STORE', 'hotel', 'STORE', 0, NOW(3), 0, NOW(3)),
  ('hotel.inventory.manage', 'hotel', 'inventory', 'manage', '维护房态与库存', 'ACTIVE', 'STORE', 'hotel', 'STORE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE
  `description` = VALUES(`description`),
  `scope_level` = VALUES(`scope_level`),
  `domain_code` = VALUES(`domain_code`),
  `grantable_levels` = VALUES(`grantable_levels`),
  `updated_at` = VALUES(`updated_at`);

-- 租户老板具备业态开通动作；店长/收银/财务具备酒店动作示例。
INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT r.`id`, p.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` r JOIN `iam_permission` p ON p.`code` = 'tenant.business.manage'
WHERE r.`code` = 'tenant.owner'
ON DUPLICATE KEY UPDATE `updated_at` = VALUES(`updated_at`);

INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT r.`id`, p.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` r JOIN `iam_permission` p ON p.`code` IN
  ('hotel.booking.view', 'hotel.booking.create', 'hotel.checkin', 'hotel.checkout', 'hotel.rate.update', 'hotel.inventory.manage')
WHERE r.`code` = 'store.manager'
ON DUPLICATE KEY UPDATE `updated_at` = VALUES(`updated_at`);

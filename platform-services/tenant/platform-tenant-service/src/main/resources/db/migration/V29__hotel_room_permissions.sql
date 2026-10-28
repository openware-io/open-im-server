-- Hotel room-state / room-availability permissions.
-- Legacy hotel.inventory.manage remains until the code cleanup migration; this
-- migration introduces the correct split and grants it to existing hotel roles.
INSERT INTO `iam_permission`
  (`code`, `module`, `resource`, `action`, `description`, `status`, `scope_level`, `domain_code`, `grantable_levels`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('hotel.room.view', 'hotel', 'room', 'view', '查看客房房态', 'ACTIVE', 'STORE', 'hotel', 'TENANT,STORE', 0, NOW(3), 0, NOW(3)),
  ('hotel.room.manage', 'hotel', 'room', 'manage', '管理客房房态/维修', 'ACTIVE', 'STORE', 'hotel', 'TENANT,STORE', 0, NOW(3), 0, NOW(3)),
  ('hotel.housekeeping.manage', 'hotel', 'housekeeping', 'manage', '管理客房清扫', 'ACTIVE', 'STORE', 'hotel', 'TENANT,STORE', 0, NOW(3), 0, NOW(3)),
  ('hotel.room.availability.view', 'hotel', 'room_availability', 'view', '查看房量', 'ACTIVE', 'STORE', 'hotel', 'TENANT,STORE', 0, NOW(3), 0, NOW(3)),
  ('hotel.room.availability.manage', 'hotel', 'room_availability', 'manage', '管理房量日历', 'ACTIVE', 'STORE', 'hotel', 'TENANT,STORE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE
  `description` = VALUES(`description`), `status` = 'ACTIVE', `updated_at` = NOW(3);

INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT r.`id`, p.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` r
JOIN `iam_permission` p ON p.`code` IN (
  'hotel.room.view',
  'hotel.room.manage',
  'hotel.housekeeping.manage',
  'hotel.room.availability.view',
  'hotel.room.availability.manage'
)
WHERE r.`code` IN ('store.manager', 'tenant.owner')
ON DUPLICATE KEY UPDATE `updated_at` = NOW(3);

INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT r.`id`, p.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` r
JOIN `iam_permission` p ON p.`code` IN ('hotel.room.view', 'hotel.housekeeping.manage')
WHERE r.`code` IN ('store.cashier', 'store.supervisor')
ON DUPLICATE KEY UPDATE `updated_at` = NOW(3);

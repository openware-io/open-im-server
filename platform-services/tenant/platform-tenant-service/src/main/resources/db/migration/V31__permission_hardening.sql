-- Hotel room/folio/housekeeping and shared-inventory permission hardening.
-- New permission split is the target; legacy hotel.inventory.manage and the
-- granular inventory codes remain granted during the transition so custom
-- tenant roles do not silently lose access before the cleanup migration.
INSERT INTO `iam_permission`
  (`code`, `module`, `resource`, `action`, `description`, `status`, `scope_level`, `domain_code`, `grantable_levels`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('hotel.folio.view', 'hotel', 'folio', 'view', '查看酒店账单', 'ACTIVE', 'STORE', 'hotel', 'STORE', 0, NOW(3), 0, NOW(3)),
  ('hotel.folio.manage', 'hotel', 'folio', 'manage', '管理酒店账单/收款', 'ACTIVE', 'STORE', 'hotel', 'STORE', 0, NOW(3), 0, NOW(3)),
  ('hotel.housekeeping.view', 'hotel', 'housekeeping', 'view', '查看客房清扫', 'ACTIVE', 'STORE', 'hotel', 'STORE', 0, NOW(3), 0, NOW(3)),
  ('inventory.view', 'inventory', 'stock', 'view', '查看商品库存', 'ACTIVE', 'TENANT', 'core', 'TENANT,STORE', 0, NOW(3), 0, NOW(3)),
  ('inventory.manage', 'inventory', 'stock', 'manage', '管理商品库存/出入库/成本', 'ACTIVE', 'TENANT', 'core', 'TENANT,STORE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE
  `description` = VALUES(`description`), `status` = 'ACTIVE',
  `scope_level` = VALUES(`scope_level`), `grantable_levels` = VALUES(`grantable_levels`), `updated_at` = NOW(3);

INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT r.`id`, p.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` r
JOIN `iam_permission` p ON p.`code` IN ('hotel.folio.view', 'hotel.folio.manage', 'hotel.housekeeping.view')
WHERE r.`code` IN ('store.manager', 'tenant.owner')
ON DUPLICATE KEY UPDATE `updated_at` = NOW(3);

INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT r.`id`, p.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` r
JOIN `iam_permission` p ON p.`code` IN ('hotel.folio.view', 'hotel.folio.manage', 'hotel.housekeeping.view')
WHERE r.`code` IN ('store.cashier', 'store.finance', 'store.supervisor')
ON DUPLICATE KEY UPDATE `updated_at` = NOW(3);

-- Custom tenant roles that already hold the legacy combined permission receive
-- the split permissions. This is an explicit authorization migration, not a
-- blanket grant.
INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT DISTINCT rp.`role_id`, p.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role_permission` rp
JOIN `iam_permission` oldp ON oldp.`id` = rp.`permission_id` AND oldp.`code` = 'hotel.inventory.manage'
JOIN `iam_permission` p ON p.`code` IN (
  'hotel.room.view', 'hotel.room.manage', 'hotel.housekeeping.view',
  'hotel.housekeeping.manage', 'hotel.room.availability.view', 'hotel.room.availability.manage'
)
ON DUPLICATE KEY UPDATE `updated_at` = NOW(3);

INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT DISTINCT rp.`role_id`, p.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role_permission` rp
JOIN `iam_permission` oldp ON oldp.`id` = rp.`permission_id`
  AND oldp.`code` IN ('inventory.material.view', 'inventory.transaction.view', 'inventory.recovery.view')
JOIN `iam_permission` p ON p.`code` = 'inventory.view'
ON DUPLICATE KEY UPDATE `updated_at` = NOW(3);

INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT DISTINCT rp.`role_id`, p.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role_permission` rp
JOIN `iam_permission` oldp ON oldp.`id` = rp.`permission_id`
  AND oldp.`code` IN ('inventory.material.manage', 'inventory.receipt.create', 'inventory.adjust', 'inventory.recovery.confirm')
JOIN `iam_permission` p ON p.`code` = 'inventory.manage'
ON DUPLICATE KEY UPDATE `updated_at` = NOW(3);

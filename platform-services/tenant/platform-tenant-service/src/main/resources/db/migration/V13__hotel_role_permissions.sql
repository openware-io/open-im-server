-- S5 酒店经营权限：给租户老板/店长/收银/财务授予酒店域权限。
INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT r.id, p.id, 0, NOW(3), 0, NOW(3)
FROM `iam_role` r
JOIN `iam_permission` p ON p.code IN (
  'hotel.booking.view', 'hotel.booking.create', 'hotel.checkin', 'hotel.checkout',
  'hotel.inventory.manage', 'hotel.rate.update'
)
WHERE r.code = 'tenant.owner' AND r.tenant_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM `iam_role_permission` rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT r.id, p.id, 0, NOW(3), 0, NOW(3)
FROM `iam_role` r
JOIN `iam_permission` p ON p.code IN (
  'hotel.booking.view', 'hotel.booking.create', 'hotel.checkin', 'hotel.checkout'
)
WHERE r.code = 'store.manager' AND r.tenant_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM `iam_role_permission` rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT r.id, p.id, 0, NOW(3), 0, NOW(3)
FROM `iam_role` r
JOIN `iam_permission` p ON p.code IN ('hotel.booking.view', 'hotel.checkin')
WHERE r.code = 'store.cashier' AND r.tenant_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM `iam_role_permission` rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT r.id, p.id, 0, NOW(3), 0, NOW(3)
FROM `iam_role` r
JOIN `iam_permission` p ON p.code = 'hotel.booking.view'
WHERE r.code = 'store.finance' AND r.tenant_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM `iam_role_permission` rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

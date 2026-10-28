-- Retire legacy hotel action permissions in favour of the split codes introduced by V29/V31.
--
--   hotel.checkin      -> hotel.room.manage
--   hotel.checkout     -> hotel.folio.manage
--   hotel.rate.update  -> hotel.room.availability.manage
--
-- Runtime code and the admin frontend no longer check the legacy codes (single source: the split
-- permissions). Roles that already hold a legacy code receive the replacement code before the
-- legacy grant is removed, so existing/custom tenant roles keep their ability to act.
-- The permission rows themselves are kept (audit trail) but set to DISABLED.

-- 1) Carry grants over to the replacement permission (explicit authorization migration).
INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT DISTINCT rp.`role_id`, p.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role_permission` rp
JOIN `iam_permission` oldp ON oldp.`id` = rp.`permission_id`
JOIN `iam_permission` p ON p.`code` = CASE oldp.`code`
    WHEN 'hotel.checkin' THEN 'hotel.room.manage'
    WHEN 'hotel.checkout' THEN 'hotel.folio.manage'
    WHEN 'hotel.rate.update' THEN 'hotel.room.availability.manage'
  END
WHERE oldp.`code` IN ('hotel.checkin', 'hotel.checkout', 'hotel.rate.update')
ON DUPLICATE KEY UPDATE `updated_at` = NOW(3);

-- 2) Remove the legacy role grants (authorization first).
DELETE rp FROM `iam_role_permission` rp
JOIN `iam_permission` p ON p.`id` = rp.`permission_id`
WHERE p.`code` IN ('hotel.checkin', 'hotel.checkout', 'hotel.rate.update');

-- 3) Disable the legacy permission codes (do not delete rows: iam_permission is audit-referenced).
UPDATE `iam_permission`
SET `status` = 'DISABLED', `updated_at` = NOW(3)
WHERE `code` IN ('hotel.checkin', 'hotel.checkout', 'hotel.rate.update');

-- 4) `order.hold` only guarded the removed `/orders/{id}/hold|unhold` endpoints (V28 drops the
--    order-level hold columns; KTV PAUSED session is the only place a hold exists now). Same
--    retire pattern: grants first, then DISABLED — no replacement permission exists.
DELETE rp FROM `iam_role_permission` rp
JOIN `iam_permission` p ON p.`id` = rp.`permission_id`
WHERE p.`code` = 'order.hold';

UPDATE `iam_permission`
SET `status` = 'DISABLED', `description` = '已废弃：挂单由 KTV 会话 PAUSED 表达，端点已删除',
    `updated_at` = NOW(3)
WHERE `code` = 'order.hold';

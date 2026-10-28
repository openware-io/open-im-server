-- Retire the legacy combined hotel permission.
--
-- `hotel.inventory.manage` used to mean "room state + hotel inventory". After the domain split
-- room state is covered by hotel.room.* / hotel.housekeeping.* and availability by
-- hotel.room.availability.*, so the combined code is no longer a valid access condition
-- (verified: no Java/前端 permission check references it since V31).
--
-- Authorization first, then disable: removing the permission row itself would break the
-- historical audit trail (iam_permission is also a reference target), so it stays as DISABLED.
DELETE rp FROM `iam_role_permission` rp
JOIN `iam_permission` p ON p.`id` = rp.`permission_id`
WHERE p.`code` = 'hotel.inventory.manage';

UPDATE `iam_permission`
SET `status` = 'DISABLED', `updated_at` = NOW(3)
WHERE `code` = 'hotel.inventory.manage';

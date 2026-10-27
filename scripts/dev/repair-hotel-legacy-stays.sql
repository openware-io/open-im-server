-- 本地/演示环境：修复“DUE_OUT 但从未入住”的历史脏预订。
-- 这类预订没有 htl_stay，也没有 htl_folio，不能显示为在住/待退房，否则前台点“账单”必然 FOLIO_NOT_FOUND。
UPDATE `htl_booking` b
LEFT JOIN `htl_stay` s ON s.`booking_id` = b.`id` AND s.`status` = 'IN_HOUSE'
SET b.`status` = 'NO_SHOW', b.`updated_at` = NOW(3)
WHERE b.`status` IN ('IN_HOUSE', 'DUE_OUT')
  AND s.`id` IS NULL
  AND b.`check_out_date` <= CURDATE();

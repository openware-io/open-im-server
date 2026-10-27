-- 修复历史上“在住客人 + DIRTY 房态”的不一致：
-- 只要同一房间存在 IN_HOUSE stay，房间必须为 OCCUPIED，不能被上一次退房遗留的清扫任务覆盖。
UPDATE `htl_room` r
JOIN `htl_stay` s ON s.`room_id` = r.`id` AND s.`status` = 'IN_HOUSE'
SET r.`status` = 'OCCUPIED', r.`updated_at` = NOW(3)
WHERE r.`status` = 'DIRTY';

-- 旧预订遗留、与当前在住 stay 不匹配的退房清扫任务，收口为 DONE；
-- 当前在住客房的 STAYOVER 任务不受影响。
UPDATE `htl_housekeeping_task` t
JOIN `htl_stay` s ON s.`room_id` = t.`room_id` AND s.`status` = 'IN_HOUSE'
SET t.`status` = 'DONE', t.`updated_at` = NOW(3)
WHERE t.`status` IN ('PENDING', 'IN_PROGRESS')
  AND t.`booking_id` <> s.`booking_id`
  AND (t.`task_type` IS NULL OR t.`task_type` = 'DEPARTURE');

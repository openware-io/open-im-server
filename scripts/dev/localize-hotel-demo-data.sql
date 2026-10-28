-- 本地/演示环境：把英文示例房型改为中文。仅本地执行，不进入 Flyway。
UPDATE `htl_room_type`
SET `name` = '豪华大床房', `updated_at` = NOW(3)
WHERE `store_id` = 102 AND `code` = 'DLX';

-- 预订表里的 room_type 是下单时的快照，历史演示数据一起改成中文，避免列表继续显示英文。
UPDATE `htl_booking`
SET `room_type` = '豪华大床房', `updated_at` = NOW(3)
WHERE `store_id` = 102 AND `room_type` = 'Deluxe King';

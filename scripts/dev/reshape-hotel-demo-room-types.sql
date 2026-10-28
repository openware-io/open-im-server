-- 本地/演示环境：把 60 间客房塑造成真实酒店常见的房型结构。
-- 仅本地执行，不进入 Flyway；可重复执行。
--
-- 8 楼  0801-0806 高级大床房 6 间
--       0807-0814 高级双床房 8 间
--       0815-0820 家庭房     6 间
-- 9 楼  0901-0908 豪华大床房 8 间
--       0909-0914 行政大床房 6 间
--       0915-0920 行政套房   6 间
-- 10 楼 1001-1006 行政大床房 6 间
--       1007-1012 行政套房   6 间
--       1013-1020 家庭房     8 间
--
-- 合计：高级大床 6、高级双床 8、豪华大床 8、行政大床 12、行政套房 12、家庭房 14。

INSERT INTO `htl_room_type`
  (`id`, `tenant_id`, `store_id`, `hotel_id`, `code`, `name`, `capacity`, `bed_type`, `area_sqm`, `status`, `version`, `created_at`, `updated_at`)
VALUES
  (1, 100, 102, 1, 'DLX',    '豪华大床房', 2, '大床',     36.00, 'ACTIVE', 0, NOW(3), NOW(3)),
  (2, 100, 102, 1, 'SUP_K',  '高级大床房', 2, '大床',     30.00, 'ACTIVE', 0, NOW(3), NOW(3)),
  (3, 100, 102, 1, 'SUP_T',  '高级双床房', 2, '双床',     32.00, 'ACTIVE', 0, NOW(3), NOW(3)),
  (4, 100, 102, 1, 'EXEC_K', '行政大床房', 2, '大床',     45.00, 'ACTIVE', 0, NOW(3), NOW(3)),
  (5, 100, 102, 1, 'EXEC_S', '行政套房',   3, '大床',     58.00, 'ACTIVE', 0, NOW(3), NOW(3)),
  (6, 100, 102, 1, 'FAMILY', '家庭房',     4, '大床+双床', 48.00, 'ACTIVE', 0, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE
  `name` = VALUES(`name`),
  `capacity` = VALUES(`capacity`),
  `bed_type` = VALUES(`bed_type`),
  `area_sqm` = VALUES(`area_sqm`),
  `status` = 'ACTIVE',
  `updated_at` = NOW(3);

UPDATE `htl_room_type`
SET `name` = '豪华大床房', `capacity` = 2, `bed_type` = '大床', `area_sqm` = 36.00, `status` = 'ACTIVE', `updated_at` = NOW(3)
WHERE `id` = 1;

UPDATE `htl_rate_plan`
SET `name` = '门市价', `currency` = 'CNY', `updated_at` = NOW(3)
WHERE `store_id` = 102 AND `code` = 'BAR';

-- 重新分配房型；范围必须和上面的楼层规划一致。
UPDATE `htl_room` SET `room_type_id` = 2, `updated_at` = NOW(3)
WHERE `store_id` = 102 AND `room_no` BETWEEN '0801' AND '0806';
UPDATE `htl_room` SET `room_type_id` = 3, `updated_at` = NOW(3)
WHERE `store_id` = 102 AND `room_no` BETWEEN '0807' AND '0814';
UPDATE `htl_room` SET `room_type_id` = 6, `updated_at` = NOW(3)
WHERE `store_id` = 102 AND `room_no` BETWEEN '0815' AND '0820';
UPDATE `htl_room` SET `room_type_id` = 1, `updated_at` = NOW(3)
WHERE `store_id` = 102 AND `room_no` BETWEEN '0901' AND '0908';
UPDATE `htl_room` SET `room_type_id` = 4, `updated_at` = NOW(3)
WHERE `store_id` = 102 AND (`room_no` BETWEEN '0909' AND '0914' OR `room_no` BETWEEN '1001' AND '1006');
UPDATE `htl_room` SET `room_type_id` = 5, `updated_at` = NOW(3)
WHERE `store_id` = 102 AND (`room_no` BETWEEN '0915' AND '0920' OR `room_no` BETWEEN '1007' AND '1012');
UPDATE `htl_room` SET `room_type_id` = 6, `updated_at` = NOW(3)
WHERE `store_id` = 102 AND `room_no` BETWEEN '1013' AND '1020';

-- 预订里的房型是下单快照；演示数据里的房号一起纠正，避免列表显示旧房型。
UPDATE `htl_booking`
SET `room_type_id` = CASE
      WHEN `room_no` BETWEEN '0801' AND '0806' THEN 2
      WHEN `room_no` BETWEEN '0807' AND '0814' THEN 3
      WHEN `room_no` BETWEEN '0815' AND '0820' THEN 6
      WHEN `room_no` BETWEEN '0901' AND '0908' THEN 1
      WHEN `room_no` BETWEEN '0909' AND '0914' THEN 4
      WHEN `room_no` BETWEEN '0915' AND '0920' THEN 5
      WHEN `room_no` BETWEEN '1001' AND '1006' THEN 4
      WHEN `room_no` BETWEEN '1007' AND '1012' THEN 5
      WHEN `room_no` BETWEEN '1013' AND '1020' THEN 6
      ELSE `room_type_id`
    END,
    `room_type` = CASE
      WHEN `room_no` BETWEEN '0801' AND '0806' THEN '高级大床房'
      WHEN `room_no` BETWEEN '0807' AND '0814' THEN '高级双床房'
      WHEN `room_no` BETWEEN '0815' AND '0820' THEN '家庭房'
      WHEN `room_no` BETWEEN '0901' AND '0908' THEN '豪华大床房'
      WHEN `room_no` BETWEEN '0909' AND '0914' THEN '行政大床房'
      WHEN `room_no` BETWEEN '0915' AND '0920' THEN '行政套房'
      WHEN `room_no` BETWEEN '1001' AND '1006' THEN '行政大床房'
      WHEN `room_no` BETWEEN '1007' AND '1012' THEN '行政套房'
      WHEN `room_no` BETWEEN '1013' AND '1020' THEN '家庭房'
      ELSE `room_type`
    END,
    `updated_at` = NOW(3)
WHERE `store_id` = 102 AND `room_no` IS NOT NULL AND `room_no` <> '';

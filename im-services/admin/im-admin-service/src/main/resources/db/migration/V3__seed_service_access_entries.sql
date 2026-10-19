-- Restore the service-access catalog that the client "Services" tab reads.
--
-- The rows live in im-admin-service and are the single source of truth for the
-- C-end service board. Links stay relative so the same configuration works on
-- local Kind (http://<host>:30080) and production without a host rewrite.
-- The client resolves these paths against AppConfig.apiBase.

-- Keep the existing categories, add the travel category used by the SaaS home.
INSERT INTO `adm_miniapp_service_type` (`name`, `sort_order`, `created_at`, `updated_at`)
SELECT '出行服务', 2, NOW(3), NOW(3)
WHERE NOT EXISTS (SELECT 1 FROM `adm_miniapp_service_type` WHERE `name` = '出行服务');

-- Replace the placeholder examples with real SaaS-backed entries.
UPDATE `adm_miniapp_service_item`
SET `link` = '/a380/#/service/delivery',
    `introduction` = 'A380 外卖：在线选餐、下单与配送',
    `status` = 1,
    `hidden` = 0,
    `updated_at` = NOW(3)
WHERE `name` = '外卖' AND `audience` = 'consumer';

UPDATE `adm_miniapp_service_item`
SET `link` = '/a380/#/service/taxi',
    `introduction` = 'A380 出行：叫车与行程服务',
    `status` = 1,
    `hidden` = 0,
    `updated_at` = NOW(3)
WHERE `name` = '打车' AND `audience` = 'consumer';

UPDATE `adm_miniapp_service_item`
SET `link` = '/a380/',
    `introduction` = 'A380 服务首页：酒店、KTV、酒吧、足浴、美食与出行',
    `status` = 1,
    `is_top` = 1,
    `hidden` = 0,
    `updated_at` = NOW(3)
WHERE `name` = 'A380 更多服务' AND `audience` = 'consumer';

-- The operator entry opens the B-end console; keep it searchable but out of the
-- consumer board, matching the audience/hidden semantics of the schema.
UPDATE `adm_miniapp_service_item`
SET `link` = '/b/',
    `introduction` = '运营后台：开台、计时、结台与收款',
    `status` = 1,
    `hidden` = 1,
    `updated_at` = NOW(3)
WHERE `name` = 'KTV 业务' AND `audience` = 'operator';

-- Consumer services exposed by the SaaS A380 home.
INSERT INTO `adm_miniapp_service_item`
  (`type_id`, `name`, `link`, `introduction`, `icon`, `status`, `is_top`, `audience`, `hidden`, `sort_order`, `created_at`, `updated_at`)
SELECT t.`id`, '酒店', '/a380/#/service/hotel', 'A380 酒店：房型查询与在线预订', NULL, 1, 0, 'consumer', 0, 1, NOW(3), NOW(3)
FROM `adm_miniapp_service_type` t
WHERE t.`name` = '休闲娱乐'
  AND NOT EXISTS (SELECT 1 FROM `adm_miniapp_service_item` i WHERE i.`name` = '酒店' AND i.`audience` = 'consumer');

INSERT INTO `adm_miniapp_service_item`
  (`type_id`, `name`, `link`, `introduction`, `icon`, `status`, `is_top`, `audience`, `hidden`, `sort_order`, `created_at`, `updated_at`)
SELECT t.`id`, 'KTV 预订', '/a380/#/service/ktv', 'A380 KTV：包厢类型查询与在线预约', NULL, 1, 0, 'consumer', 0, 2, NOW(3), NOW(3)
FROM `adm_miniapp_service_type` t
WHERE t.`name` = '休闲娱乐'
  AND NOT EXISTS (SELECT 1 FROM `adm_miniapp_service_item` i WHERE i.`name` = 'KTV 预订' AND i.`audience` = 'consumer');

INSERT INTO `adm_miniapp_service_item`
  (`type_id`, `name`, `link`, `introduction`, `icon`, `status`, `is_top`, `audience`, `hidden`, `sort_order`, `created_at`, `updated_at`)
SELECT t.`id`, '酒吧', '/a380/#/service/bar', 'A380 酒吧：散台与卡座预约', NULL, 1, 0, 'consumer', 0, 3, NOW(3), NOW(3)
FROM `adm_miniapp_service_type` t
WHERE t.`name` = '休闲娱乐'
  AND NOT EXISTS (SELECT 1 FROM `adm_miniapp_service_item` i WHERE i.`name` = '酒吧' AND i.`audience` = 'consumer');

INSERT INTO `adm_miniapp_service_item`
  (`type_id`, `name`, `link`, `introduction`, `icon`, `status`, `is_top`, `audience`, `hidden`, `sort_order`, `created_at`, `updated_at`)
SELECT t.`id`, '台球', '/a380/#/service/billiards', 'A380 台球：球台预约与到店服务', NULL, 1, 0, 'consumer', 0, 4, NOW(3), NOW(3)
FROM `adm_miniapp_service_type` t
WHERE t.`name` = '休闲娱乐'
  AND NOT EXISTS (SELECT 1 FROM `adm_miniapp_service_item` i WHERE i.`name` = '台球' AND i.`audience` = 'consumer');

INSERT INTO `adm_miniapp_service_item`
  (`type_id`, `name`, `link`, `introduction`, `icon`, `status`, `is_top`, `audience`, `hidden`, `sort_order`, `created_at`, `updated_at`)
SELECT t.`id`, '足浴', '/a380/#/service/foot', 'A380 足浴：项目与技师预约', NULL, 1, 0, 'consumer', 0, 5, NOW(3), NOW(3)
FROM `adm_miniapp_service_type` t
WHERE t.`name` = '休闲娱乐'
  AND NOT EXISTS (SELECT 1 FROM `adm_miniapp_service_item` i WHERE i.`name` = '足浴' AND i.`audience` = 'consumer');

INSERT INTO `adm_miniapp_service_item`
  (`type_id`, `name`, `link`, `introduction`, `icon`, `status`, `is_top`, `audience`, `hidden`, `sort_order`, `created_at`, `updated_at`)
SELECT t.`id`, '美食', '/a380/#/service/food', 'A380 美食：到店餐饮与点单', NULL, 1, 0, 'consumer', 0, 6, NOW(3), NOW(3)
FROM `adm_miniapp_service_type` t
WHERE t.`name` = '休闲娱乐'
  AND NOT EXISTS (SELECT 1 FROM `adm_miniapp_service_item` i WHERE i.`name` = '美食' AND i.`audience` = 'consumer');

INSERT INTO `adm_miniapp_service_item`
  (`type_id`, `name`, `link`, `introduction`, `icon`, `status`, `is_top`, `audience`, `hidden`, `sort_order`, `created_at`, `updated_at`)
SELECT t.`id`, '闪送', '/a380/#/service/flash', 'A380 闪送：同城即时配送', NULL, 1, 0, 'consumer', 0, 2, NOW(3), NOW(3)
FROM `adm_miniapp_service_type` t
WHERE t.`name` = '便民服务'
  AND NOT EXISTS (SELECT 1 FROM `adm_miniapp_service_item` i WHERE i.`name` = '闪送' AND i.`audience` = 'consumer');

INSERT INTO `adm_miniapp_service_item`
  (`type_id`, `name`, `link`, `introduction`, `icon`, `status`, `is_top`, `audience`, `hidden`, `sort_order`, `created_at`, `updated_at`)
SELECT t.`id`, '机票', '/a380/#/service/flights', 'A380 机票：国内航班查询与预订', NULL, 1, 0, 'consumer', 0, 0, NOW(3), NOW(3)
FROM `adm_miniapp_service_type` t
WHERE t.`name` = '出行服务'
  AND NOT EXISTS (SELECT 1 FROM `adm_miniapp_service_item` i WHERE i.`name` = '机票' AND i.`audience` = 'consumer');

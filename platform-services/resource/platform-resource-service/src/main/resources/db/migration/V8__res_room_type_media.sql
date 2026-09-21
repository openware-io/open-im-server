-- 房型图片：C 端「选择包厢类型」直接展示**房型**（不再是包厢号），房型需要自己的样板图，
-- 否则卡片只能借用某个包厢的主图（V6 之后 C 端按包厢归并补图），房型一改图就要去改包厢。
--
-- 列形态与包厢/资源完全一致（见 V5__res_resource_media.sql，与商品/物料同一口径）：
--   image_urls     JSON 数组，最多 9 张（业务侧 ResourceMedia 校验，错误码 RESOURCE_INVALID）
--   main_image_url 主图 URL，必须属于 image_urls（列表非空且未指定主图时自动取第一张）
--
-- 只加列、不修改历史迁移、不动已有列；存量房型保持 NULL，C 端继续走「包厢样板图 > 门店占位图」的降级链，
-- 因此本次改动不影响任何既有展示与计费行为。
ALTER TABLE `res_room_type`
  ADD COLUMN `image_urls` json NULL COMMENT '房型图片URL列表(JSON数组，最多9张)' AFTER `capacity`,
  ADD COLUMN `main_image_url` varchar(512) NULL COMMENT '房型主图URL(必须属于图片列表)' AFTER `image_urls`;

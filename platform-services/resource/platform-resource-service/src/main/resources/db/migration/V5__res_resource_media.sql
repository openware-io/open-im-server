-- 包厢（资源）图片与描述：包厢管理需要像商品一样维护多图与一段说明文字。
-- 列形态与商品/物料保持一致（见 V13__ord_product_material_images.sql / ord_inventory_material.description）：
--   image_urls     JSON 数组，最多 9 张（业务侧 ResourceMedia 校验）
--   main_image_url 主图 URL，必须属于 image_urls（列表非空且未指定主图时取第一张）
--   description    描述，≤255（与商品/物料同一长度口径）
-- 只加列、不修改历史迁移、不动已有列；存量数据保持 NULL（无图/无描述时前端走占位与空态）。
ALTER TABLE `res_resource`
  ADD COLUMN `image_urls` json NULL COMMENT '资源图片URL列表(JSON数组，最多9张)' AFTER `attributes_json`,
  ADD COLUMN `main_image_url` varchar(512) NULL COMMENT '资源主图URL(必须属于图片列表)' AFTER `image_urls`,
  ADD COLUMN `description` varchar(255) NULL COMMENT '资源描述' AFTER `main_image_url`;

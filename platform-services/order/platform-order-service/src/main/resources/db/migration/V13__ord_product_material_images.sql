-- 商品/物料多图：图片 URL 列表（JSON 数组，最多 9 张）+ 主图 URL（必须属于列表，列表非空时恰好一张）。
ALTER TABLE `ord_product`
  ADD COLUMN `image_urls` json NULL COMMENT '商品图片URL列表(JSON数组，最多9张)' AFTER `description`,
  ADD COLUMN `main_image_url` varchar(512) NULL COMMENT '商品主图URL' AFTER `image_urls`;

ALTER TABLE `ord_inventory_material`
  ADD COLUMN `image_urls` json NULL COMMENT '物料图片URL列表(JSON数组，最多9张)' AFTER `safety_stock`,
  ADD COLUMN `main_image_url` varchar(512) NULL COMMENT '物料主图URL' AFTER `image_urls`;

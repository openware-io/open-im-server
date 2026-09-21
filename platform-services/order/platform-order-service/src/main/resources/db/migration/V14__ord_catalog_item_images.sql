-- 点单目录图片：B 端点单与 C 端自助加项需要缩略图，方便用户判断所购商品/服务。
-- 目录项图片是「关联商品优先、商品无图时取关联物料」的镜像（见 ProductApplicationService.syncCatalog /
-- InventoryApplicationService.updateMaterial），列形态与 ord_product / ord_inventory_material 保持一致：
-- image_urls JSON 数组（最多 9 张，业务侧 ItemImages 校验）+ main_image_url 主图。
ALTER TABLE `ord_catalog_item`
  ADD COLUMN `image_urls` json NULL COMMENT '目录项图片URL列表(JSON数组，最多9张，镜像自关联商品/物料)' AFTER `description`,
  ADD COLUMN `main_image_url` varchar(512) NULL COMMENT '目录项主图URL(镜像自关联商品/物料)' AFTER `image_urls`;

-- 存量回填（一次性）：先按目录项直接关联的商品回填；
-- 再对仍无图的目录项，用「商品 → 物料」链路回填（物料加项场景：图片只维护在物料上）。
-- 用 CAST(... AS CHAR) 显式把 JSON 列转成合法 JSON 文本再写入 JSON 列，避免跨列 JSON 赋值的类型差异。
UPDATE `ord_catalog_item` `c`
  JOIN `ord_product` `p`
    ON `p`.`tenant_id` = `c`.`tenant_id` AND `p`.`id` = `c`.`product_id`
   SET `c`.`image_urls` = CAST(`p`.`image_urls` AS CHAR),
       `c`.`main_image_url` = `p`.`main_image_url`
 WHERE `c`.`product_id` IS NOT NULL
   AND `p`.`image_urls` IS NOT NULL;

UPDATE `ord_catalog_item` `c`
  JOIN `ord_product` `p`
    ON `p`.`tenant_id` = `c`.`tenant_id` AND `p`.`id` = `c`.`product_id`
  JOIN `ord_inventory_material` `m`
    ON `m`.`tenant_id` = `p`.`tenant_id` AND `m`.`store_id` = `p`.`store_id` AND `m`.`id` = `p`.`material_id`
   SET `c`.`image_urls` = CAST(`m`.`image_urls` AS CHAR),
       `c`.`main_image_url` = `m`.`main_image_url`
 WHERE `c`.`product_id` IS NOT NULL
   AND `c`.`image_urls` IS NULL
   AND `m`.`image_urls` IS NOT NULL;

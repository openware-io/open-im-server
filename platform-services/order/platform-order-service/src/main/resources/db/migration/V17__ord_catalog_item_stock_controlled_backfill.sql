-- F3 库存失效修复（点单目录「是否扣库存」口径统一）：
-- 现象：ord_catalog_item.stock_controlled 全为 0（V11 只给了列默认值 0，没跟着商品回填），
-- 而实物商品 ord_product.stock_controlled = 1。目录项是点单列表与加项的入口，
-- 它判定「不受库存控制」就导致：加项不扣库存、库存 0 仍可点（超卖）、售罄不显示。
--
-- 修复口径：库存/售罄一律以「商品 + 物料」为准，目录项该列只作查询缓存，因此这里把缓存回填成与商品一致。
-- 1) 商品侧回指（ord_product.catalog_item_id）为权威关联：回填 stock_controlled，并把目录项 product_id 补齐；
UPDATE `ord_catalog_item` c
  JOIN `ord_product` p
    ON p.`catalog_item_id` = c.`id`
   AND p.`tenant_id` = c.`tenant_id`
   SET c.`stock_controlled` = p.`stock_controlled`,
       c.`product_id` = p.`id`;

-- 2) 兼容只有目录项侧回指（ord_catalog_item.product_id）的历史数据。
UPDATE `ord_catalog_item` c
  JOIN `ord_product` p
    ON p.`id` = c.`product_id`
   AND p.`tenant_id` = c.`tenant_id`
   SET c.`stock_controlled` = p.`stock_controlled`;

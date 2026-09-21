-- 仓库管理需要增加「采购价」：ord_inventory_material 增加 purchase_price 列。
--
-- 列形态与仓库/订单其它金额列一致（ord_product.sale_price、ord_catalog_item.unit_price）：
-- decimal(20,6)，单位是**最小货币单位（分）**，含义是**每一计量单位（unit）**的采购价。
-- 后台运营侧一律按「元」录入与展示，进入本列前由调用方换算成分（见 InventoryApplicationService）。
--
-- 允许 NULL：历史物料与「暂不维护采购价」的物料都保持 NULL，与「采购价为 0 分」区分开。
-- 写入侧约定（InventoryApplicationService#normalizePurchasePrice）：
--   null = 本次不修改采购价（部分更新语义，新建时即「未填」）；
--   0    = 清空采购价，落库 NULL（而不是写 0 分）；
--   > 0  = 实际采购价，为负或超过上限一律 400 PURCHASE_PRICE_INVALID。
--
-- 只加列、不改历史迁移、不 DROP、不动已有列；历史数据保持 NULL。
ALTER TABLE `ord_inventory_material`
  ADD COLUMN `purchase_price` decimal(20,6) NULL COMMENT '采购价（最小货币单位：分，每计量单位）' AFTER `safety_stock`;

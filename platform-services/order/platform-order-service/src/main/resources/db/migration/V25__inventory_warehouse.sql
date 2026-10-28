-- Inventory scope hardening.
-- Material/SKU is a store-level shared master (water/towel can be shared by
-- hotel and KTV); stock and transactions carry the business_type/warehouse
-- scope. Clean Kind rebuilds seed warehouses and stock, so no cross-service
-- backfill or joins are allowed here.
ALTER TABLE `ord_inventory_stock`
  MODIFY COLUMN `organization_id` bigint unsigned NOT NULL COMMENT '组织快照',
  MODIFY COLUMN `business_type` varchar(32) NOT NULL COMMENT '业态快照',
  MODIFY COLUMN `warehouse_id` bigint unsigned NOT NULL COMMENT '仓库',
  DROP INDEX `uk_ord_inventory_stock_material`,
  ADD UNIQUE KEY `uk_ord_inventory_stock_scope`
    (`tenant_id`, `business_type`, `store_id`, `warehouse_id`, `material_id`);

ALTER TABLE `ord_inventory_transaction`
  MODIFY COLUMN `organization_id` bigint unsigned NOT NULL COMMENT '组织快照',
  MODIFY COLUMN `business_type` varchar(32) NOT NULL COMMENT '业态快照',
  MODIFY COLUMN `warehouse_id` bigint unsigned NOT NULL COMMENT '仓库',
  ADD KEY `idx_ord_inventory_transaction_warehouse`
    (`tenant_id`, `business_type`, `store_id`, `warehouse_id`, `material_id`, `created_at`);

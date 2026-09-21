-- 商品分「实物商品 / 服务」两类（第 3 点需求）：
--   item_type        PRODUCT = 实物商品（默认，历史行一律归此类）；SERVICE = 服务（人员的服务）。
--   server_resource_id 服务型商品关联的服务人员（res_resource.id，resource_type=KTV_SERVER）。
--
-- 为什么把「服务人员 ↔ 目录项」的权威关联放在商品侧（ord_product）：
--   1) 资源域 res_resource 没有 attributes_json 列，且资源/后台两侧都没有读写它的代码
--      （platform-admin-service 的 ServerCatalogItem.catalogItemId 恒为 null，注释「catalog 领域端点待补」）；
--   2) order 域已经有「商品 → 目录项」的权威同步链路（ord_product.catalog_item_id ↔ ord_catalog_item.product_id，
--      见 ProductApplicationService.syncCatalog 与 V17 回填），服务人员只需挂在商品上，
--      目录项即可由 ord_catalog_item.product_id 反查，关联唯一且不需要第二套关联表/资源侧写路径。
-- 唯一性由 uk_ord_product_server_resource 兜底：一个服务人员在一个门店下只能被一个商品占用。
-- 实物商品（以及未填的默认值）该列为 NULL，MySQL 唯一索引允许多个 NULL，因此不影响实物商品。
ALTER TABLE `ord_product`
  ADD COLUMN `item_type` varchar(16) NOT NULL DEFAULT 'PRODUCT' COMMENT '商品类型：PRODUCT 实物商品 / SERVICE 服务' AFTER `category`,
  ADD COLUMN `server_resource_id` bigint unsigned NULL COMMENT '服务人员ID(res_resource.id，resource_type=KTV_SERVER；仅 item_type=SERVICE)' AFTER `material_id`;

-- 历史行一律回填 PRODUCT（列默认值已保证，这里显式回填做双保险：禁止 NULL/空串残留）。
UPDATE `ord_product` SET `item_type` = 'PRODUCT' WHERE `item_type` IS NULL OR `item_type` = '';

-- 同一服务人员在同一门店只能关联一个服务商品（商品侧为权威来源；应用层会先给出可读的中文 409，
-- 本唯一键是并发下的最后一道防线）。
ALTER TABLE `ord_product`
  ADD UNIQUE KEY `uk_ord_product_server_resource` (`tenant_id`,`store_id`,`server_resource_id`);

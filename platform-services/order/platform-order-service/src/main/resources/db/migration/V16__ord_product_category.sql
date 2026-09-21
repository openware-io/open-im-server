-- 商品分类：商品管理页的分类字典，按门店维护（不新增菜单/权限，入口在商品管理页）。
-- 商品仍以名称冗余引用（ord_product.category），因此改名时由应用服务同步商品与点单目录项，
-- 避免出现「分类列表里没有、商品却引用着」的孤儿分类；被商品引用的分类禁止删除。
CREATE TABLE `ord_product_category` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '分类ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `name` varchar(64) NOT NULL COMMENT '分类名称',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '排序（越小越靠前）',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_product_category_store_name` (`tenant_id`,`store_id`,`name`),
  KEY `idx_ord_product_category_store_status` (`tenant_id`,`store_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KTV商品分类';

-- KTV 商品库存子域：物料、库存余额、库存流水与商品上架状态。
CREATE TABLE `ord_inventory_material` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '物料ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `material_code` varchar(64) NOT NULL COMMENT '门店内物料编码',
  `name` varchar(255) NOT NULL COMMENT '物料名称',
  `category` varchar(64) NOT NULL COMMENT '物料分类',
  `unit` varchar(32) NOT NULL COMMENT '计量单位',
  `safety_stock` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '安全库存',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_inventory_material_store_code` (`tenant_id`,`store_id`,`material_code`),
  KEY `idx_ord_inventory_material_store_status` (`tenant_id`,`store_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存物料档案';

CREATE TABLE `ord_inventory_stock` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '库存余额ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `material_id` bigint unsigned NOT NULL COMMENT '物料ID',
  `on_hand_qty` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '现有库存',
  `reserved_qty` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '预占库存',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_inventory_stock_material` (`tenant_id`,`store_id`,`material_id`),
  KEY `idx_ord_inventory_stock_store` (`tenant_id`,`store_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存余额';

CREATE TABLE `ord_inventory_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '库存流水ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `material_id` bigint unsigned NOT NULL COMMENT '物料ID',
  `transaction_type` varchar(24) NOT NULL COMMENT 'RECEIPT/CONSUME/ADJUST_IN/ADJUST_OUT/REVERSE',
  `quantity_delta` decimal(20,6) NOT NULL COMMENT '库存变化量',
  `quantity_before` decimal(20,6) NOT NULL COMMENT '变化前库存',
  `quantity_after` decimal(20,6) NOT NULL COMMENT '变化后库存',
  `source_type` varchar(32) NOT NULL COMMENT 'RECEIPT/ORDER_ITEM/ADJUSTMENT',
  `source_id` varchar(64) NULL COMMENT '来源业务ID',
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键',
  `reason` varchar(500) NULL COMMENT '原因',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '操作人',
  `created_at` datetime(3) NOT NULL COMMENT '发生时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_inventory_tx_idempotency` (`tenant_id`,`idempotency_key`),
  KEY `idx_ord_inventory_tx_material_time` (`tenant_id`,`store_id`,`material_id`,`created_at`),
  KEY `idx_ord_inventory_tx_source` (`tenant_id`,`source_type`,`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存变动流水';

CREATE TABLE `ord_product` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '商品ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `product_code` varchar(64) NOT NULL COMMENT '门店内商品编码',
  `name` varchar(255) NOT NULL COMMENT '商品名称',
  `category` varchar(64) NOT NULL COMMENT '商品分类',
  `unit` varchar(32) NOT NULL COMMENT '销售单位',
  `sale_price` decimal(20,6) NOT NULL COMMENT '销售单价（分）',
  `material_id` bigint unsigned NULL COMMENT '关联库存物料',
  `stock_controlled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否扣库存',
  `catalog_item_id` bigint unsigned NULL COMMENT '兼容目录项ID',
  `status` varchar(24) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ON_SHELF/OFF_SHELF',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '排序',
  `description` varchar(500) NULL COMMENT '描述',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_product_store_code` (`tenant_id`,`store_id`,`product_code`),
  UNIQUE KEY `uk_ord_product_catalog_item` (`tenant_id`,`catalog_item_id`),
  KEY `idx_ord_product_store_status` (`tenant_id`,`store_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KTV可售商品';

ALTER TABLE `ord_catalog_item`
  ADD COLUMN `product_id` bigint unsigned NULL COMMENT '库存商品ID' AFTER `id`,
  ADD COLUMN `stock_controlled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否受库存控制' AFTER `status`,
  ADD KEY `idx_ord_catalog_product_status` (`tenant_id`,`store_id`,`product_id`,`status`);

ALTER TABLE `ord_order_item`
  ADD COLUMN `inventory_status` varchar(24) NOT NULL DEFAULT 'NOT_APPLICABLE' COMMENT '库存处理状态' AFTER `source`,
  ADD COLUMN `inventory_material_id` bigint unsigned NULL COMMENT '扣减物料ID' AFTER `inventory_status`,
  ADD COLUMN `inventory_recovery_decision` varchar(24) NULL COMMENT '库存回补决定：RECOVERED/NOT_RECOVERED' AFTER `inventory_material_id`,
  ADD COLUMN `inventory_recovery_reason` varchar(500) NULL COMMENT '库存回补决定原因' AFTER `inventory_recovery_decision`,
  ADD COLUMN `inventory_recovery_decided_by` bigint unsigned NULL COMMENT '库存回补决定人' AFTER `inventory_recovery_reason`,
  ADD COLUMN `inventory_recovery_decided_at` datetime(3) NULL COMMENT '库存回补决定时间' AFTER `inventory_recovery_decided_by`;

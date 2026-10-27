-- Domain boundary foundation.
-- Additive during the refactor; the final cleanup migration drops legacy columns.
-- No cross-service table joins: the clean Kind rebuild seeds organization/business
-- snapshots explicitly. This service never reads another service's tables in DDL.
--
-- Scope:
--   * ord_order: trade_status / refund_status
--   * htl_booking: business_type + organization snapshot + one order per booking
--   * htl_room: occupancy / housekeeping / out-of-order state
--   * htl_room_hold: business holds (reservation / group / house use / OOO / locked)
--   * htl_room_availability(+hold): room availability, never called inventory
--   * ord_ktv_session: organization snapshot + one order per session
--   * inventory: organization/business scope on stock and transactions

ALTER TABLE `ord_order`
  ADD COLUMN `trade_status` varchar(32) NOT NULL DEFAULT 'DRAFT'
    COMMENT '交易状态 DRAFT/PENDING_PAYMENT/PAID/COMPLETED/CANCELLED/VOIDED' AFTER `status`,
  ADD COLUMN `refund_status` varchar(16) NOT NULL DEFAULT 'NONE'
    COMMENT '退款状态 NONE/PARTIAL/FULL' AFTER `trade_status`;

UPDATE `ord_order`
SET `trade_status` = CASE
      WHEN `status` = 'CANCELLED' THEN 'CANCELLED'
      WHEN `status` = 'VOIDED' THEN 'VOIDED'
      WHEN `status` = 'COMPLETED' THEN 'COMPLETED'
      WHEN `paid_amount` > 0 AND `paid_amount` >= `total_amount` THEN 'COMPLETED'
      WHEN `status` IN ('WAITING_PAYMENT', 'WAITING_SETTLEMENT', 'SERVING') THEN 'PENDING_PAYMENT'
      ELSE 'DRAFT'
    END,
    `refund_status` = CASE
      WHEN `status` = 'PARTIAL_REFUNDED' THEN 'PARTIAL'
      WHEN `status` = 'REFUNDED' THEN 'FULL'
      ELSE 'NONE'
    END;

ALTER TABLE `ord_order`
  ADD KEY `idx_ord_order_biz_trade` (`tenant_id`, `business_type`, `store_id`, `trade_status`, `created_at`);

ALTER TABLE `htl_booking`
  ADD COLUMN `organization_id` bigint unsigned NULL COMMENT '组织快照' AFTER `store_id`,
  ADD COLUMN `business_type` varchar(32) NOT NULL DEFAULT 'HOTEL' COMMENT '业态快照，必须等于门店业态' AFTER `organization_id`;

UPDATE `htl_booking`
SET `status` = CASE `status`
      WHEN 'PENDING_PAYMENT' THEN 'PENDING_CONFIRM'
      WHEN 'PENDING_DEPOSIT' THEN 'PENDING_CONFIRM'
      WHEN 'ARRIVAL' THEN 'CONFIRMED'
      WHEN 'IN_HOUSE' THEN 'CHECKED_IN'
      ELSE `status`
    END;

ALTER TABLE `htl_booking`
  ADD UNIQUE KEY `uk_htl_booking_order` (`order_id`);

ALTER TABLE `htl_room`
  ADD COLUMN `occupancy_status` varchar(24) NOT NULL DEFAULT 'VACANT'
    COMMENT 'VACANT/OCCUPIED' AFTER `status`,
  ADD COLUMN `housekeeping_status` varchar(24) NOT NULL DEFAULT 'CLEAN'
    COMMENT 'CLEAN/DIRTY/CLEANING/INSPECTED' AFTER `occupancy_status`,
  ADD COLUMN `out_of_order_status` varchar(24) NOT NULL DEFAULT 'NONE'
    COMMENT 'NONE/OUT_OF_ORDER/OUT_OF_SERVICE' AFTER `housekeeping_status`;

UPDATE `htl_room`
SET `occupancy_status` = CASE WHEN `status` = 'OCCUPIED' THEN 'OCCUPIED' ELSE 'VACANT' END,
    `housekeeping_status` = CASE WHEN `status` = 'DIRTY' THEN 'DIRTY' ELSE 'CLEAN' END,
    `out_of_order_status` = CASE WHEN `status` = 'MAINTENANCE' THEN 'OUT_OF_ORDER' ELSE 'NONE' END;

CREATE TABLE `htl_room_hold` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `organization_id` bigint unsigned NULL COMMENT '组织快照',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `room_id` bigint unsigned NOT NULL COMMENT '客房ID',
  `booking_id` bigint unsigned NULL COMMENT '关联预订ID',
  `reason` varchar(32) NOT NULL COMMENT 'RESERVED/GROUP_BLOCK/HOUSE_USE/MAINTENANCE/OUT_OF_SERVICE/LOCKED',
  `start_at` datetime(3) NULL COMMENT '占用开始时间',
  `end_at` datetime(3) NULL COMMENT '占用结束时间',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/RELEASED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人ID',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_htl_room_hold_room` (`tenant_id`, `store_id`, `room_id`, `status`),
  KEY `idx_htl_room_hold_booking` (`tenant_id`, `store_id`, `booking_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客房不可售/保留占用';

CREATE TABLE `htl_room_availability` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `organization_id` bigint unsigned NULL COMMENT '组织快照',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `room_type_id` bigint unsigned NOT NULL COMMENT '房型ID',
  `stay_date` date NOT NULL COMMENT '入住日期',
  `total` int NOT NULL DEFAULT 0 COMMENT '总房量',
  `sold` int NOT NULL DEFAULT 0 COMMENT '已售房量',
  `held` int NOT NULL DEFAULT 0 COMMENT '占用房量',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人ID',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_htl_room_availability_day` (`tenant_id`, `store_id`, `room_type_id`, `stay_date`),
  KEY `idx_htl_room_availability_date` (`tenant_id`, `store_id`, `stay_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='房量：房型×日期可售数量，不是商品库存';

CREATE TABLE `htl_room_availability_hold` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `organization_id` bigint unsigned NULL COMMENT '组织快照',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `booking_id` bigint unsigned NOT NULL COMMENT '预订ID',
  `room_type_id` bigint unsigned NOT NULL COMMENT '房型ID',
  `stay_date` date NOT NULL COMMENT '入住日期',
  `rooms` int NOT NULL DEFAULT 1 COMMENT '占用房间数',
  `status` varchar(24) NOT NULL DEFAULT 'HELD' COMMENT 'HELD/SOLD/RELEASED',
  `expires_at` datetime(3) NOT NULL COMMENT '占用过期时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人ID',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_htl_room_availability_hold_night` (`booking_id`, `stay_date`),
  KEY `idx_htl_room_availability_hold_expiry` (`status`, `expires_at`),
  KEY `idx_htl_room_availability_hold_room_date` (`room_type_id`, `stay_date`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='房量占用账本';

ALTER TABLE `ord_ktv_session`
  ADD COLUMN `organization_id` bigint unsigned NULL COMMENT '组织快照' AFTER `tenant_id`,
  ADD UNIQUE KEY `uk_ord_ktv_session_order` (`order_id`);

ALTER TABLE `ord_inventory_stock`
  ADD COLUMN `organization_id` bigint unsigned NULL COMMENT '组织快照' AFTER `tenant_id`,
  ADD COLUMN `business_type` varchar(32) NULL COMMENT '业态快照' AFTER `organization_id`,
  ADD COLUMN `warehouse_id` bigint unsigned NULL COMMENT '仓库' AFTER `store_id`;

ALTER TABLE `ord_inventory_transaction`
  ADD COLUMN `organization_id` bigint unsigned NULL COMMENT '组织快照' AFTER `tenant_id`,
  ADD COLUMN `business_type` varchar(32) NULL COMMENT '业态快照' AFTER `organization_id`,
  ADD COLUMN `warehouse_id` bigint unsigned NULL COMMENT '仓库' AFTER `store_id`;

CREATE TABLE `ord_inventory_warehouse` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '仓库ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `organization_id` bigint unsigned NULL COMMENT '组织快照',
  `business_type` varchar(32) NOT NULL COMMENT '业态快照',
  `store_id` bigint unsigned NOT NULL COMMENT '门店',
  `code` varchar(64) NOT NULL COMMENT '仓库编码',
  `name` varchar(128) NOT NULL COMMENT '仓库名称',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/SUSPENDED/CLOSED',
  `version` int NOT NULL DEFAULT 0,
  `created_by` bigint unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_inventory_warehouse_scope` (`tenant_id`, `business_type`, `store_id`, `code`),
  KEY `idx_ord_inventory_warehouse_store` (`tenant_id`, `store_id`, `business_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通用库存仓库（酒店/KTV共用）';


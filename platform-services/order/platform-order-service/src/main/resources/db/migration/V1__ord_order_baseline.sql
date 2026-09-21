-- 统一订单基线（首发 KTV）
CREATE TABLE `ord_order` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '订单ID',
  `tenant_id` bigint unsigned NOT NULL, `organization_id` bigint unsigned NOT NULL, `store_id` bigint unsigned NOT NULL,
  `order_no` varchar(64) NOT NULL COMMENT '订单号',
  `business_type` varchar(32) NOT NULL COMMENT '业态 KTV/HOTEL/SPA/RETAIL',
  `customer_id` bigint unsigned NULL COMMENT '客户',
  `status` varchar(32) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/WAITING_PAYMENT/WAITING_ARRIVAL/SERVING/WAITING_SETTLEMENT/COMPLETED/CANCELLED/VOIDED/PARTIAL_REFUNDED/REFUNDED',
  `currency_code` char(3) NOT NULL COMMENT '币种',
  `subtotal_amount` decimal(20,6) NOT NULL DEFAULT 0, `discount_amount` decimal(20,6) NOT NULL DEFAULT 0,
  `tax_amount` decimal(20,6) NOT NULL DEFAULT 0, `total_amount` decimal(20,6) NOT NULL DEFAULT 0,
  `paid_amount` decimal(20,6) NOT NULL DEFAULT 0, `refundable_amount` decimal(20,6) NOT NULL DEFAULT 0,
  `completed_at` datetime(3) NULL, `cancelled_at` datetime(3) NULL, `version` int NOT NULL DEFAULT 0,
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_ord_order_tenant_no` (`tenant_id`, `order_no`),
  KEY `idx_ord_order_store_status` (`tenant_id`, `store_id`, `status`, `created_at`),
  KEY `idx_ord_order_customer` (`tenant_id`, `customer_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一订单';

CREATE TABLE `ord_order_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `tenant_id` bigint unsigned NOT NULL, `order_id` bigint unsigned NOT NULL,
  `item_type` varchar(32) NOT NULL COMMENT 'PRODUCT/SERVICE/PACKAGE/ROOM_FEE/ADD_ON/TAX/FEE',
  `catalog_item_id` bigint unsigned NULL, `resource_id` bigint unsigned NULL,
  `name_snapshot` varchar(255) NOT NULL COMMENT '名称快照',
  `unit_price` decimal(20,6) NOT NULL, `quantity` decimal(20,6) NOT NULL,
  `discount_amount` decimal(20,6) NOT NULL DEFAULT 0, `tax_amount` decimal(20,6) NOT NULL DEFAULT 0, `total_amount` decimal(20,6) NOT NULL,
  `price_snapshot_json` json NULL, `status` varchar(24) NOT NULL DEFAULT 'ACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_ord_order_item_order` (`tenant_id`, `order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单明细';

CREATE TABLE `ord_ktv_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `tenant_id` bigint unsigned NOT NULL, `order_id` bigint unsigned NOT NULL,
  `room_resource_id` bigint unsigned NOT NULL COMMENT '包厢资源',
  `reserved_start_at` datetime(3) NULL, `reserved_end_at` datetime(3) NULL,
  `opened_at` datetime(3) NULL, `closed_at` datetime(3) NULL,
  `billing_unit` varchar(16) NOT NULL DEFAULT 'HOUR' COMMENT 'HOUR/HALF_HOUR/PACKAGE',
  `billing_start_at` datetime(3) NULL COMMENT '起算时间',
  `free_wait_minutes` int NOT NULL DEFAULT 0, `paused_seconds` int NOT NULL DEFAULT 0,
  `overtime_rate` decimal(20,6) NOT NULL DEFAULT 1.0,
  `billing_rule_snapshot_json` json NULL, `status` varchar(24) NOT NULL DEFAULT 'RESERVED' COMMENT 'RESERVED/OPEN/PAUSED/CLOSED/CANCELLED',
  `version` int NOT NULL DEFAULT 0,
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_ord_ktv_session_room` (`tenant_id`, `room_resource_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KTV包厢会话';

CREATE TABLE `ord_ktv_server_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `tenant_id` bigint unsigned NOT NULL, `order_id` bigint unsigned NOT NULL,
  `ktv_session_id` bigint unsigned NULL COMMENT '关联包厢会话',
  `server_resource_id` bigint unsigned NOT NULL COMMENT '服务人员资源',
  `catalog_item_id` bigint unsigned NULL COMMENT '服务目录item',
  `ordered_at` datetime(3) NULL, `started_at` datetime(3) NULL, `ended_at` datetime(3) NULL,
  `billing_unit` varchar(16) NOT NULL DEFAULT 'HOUR', `increment_minutes` int NOT NULL DEFAULT 30,
  `rounding_direction` varchar(24) NOT NULL DEFAULT 'CONSUMER_FAVOR',
  `price_per_inc` bigint NOT NULL COMMENT '每递增粒度单价（最小货币单位）',
  `duration_seconds` int NOT NULL DEFAULT 0, `duration_minutes` int NOT NULL DEFAULT 0,
  `total_amount` decimal(20,6) NOT NULL DEFAULT 0, `price_snapshot_json` json NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ORDERED' COMMENT 'ORDERED/SERVING/ENDED/CANCELLED',
  `version` int NOT NULL DEFAULT 0,
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_ord_ktv_server_order` (`tenant_id`, `order_id`),
  KEY `idx_ord_ktv_server_server` (`tenant_id`, `server_resource_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KTV服务人员点单';

-- C 端预约迁移（E-MIG）· SaaS 侧 ord_reservation 建表（SAAS_PLATFORM_04 §6.3）
-- 目标库：open_saas（platform-order-service），Flyway 历史表 flyway_schema_history_order。
-- 说明：仅新建 SaaS 侧表，不改动 im-order-service（IM 库）已执行的 V1/V2 迁移。
CREATE TABLE `ord_reservation` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '预约ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID（租户隔离）',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `reservation_no` varchar(64) NOT NULL COMMENT '预约单号',
  `customer_id` bigint unsigned NULL COMMENT '租户客户ID（cst_customer，关联 SaaS 账号 account_id）',
  `business_type` varchar(32) NOT NULL COMMENT '业态 KTV/HOTEL/SPA/MASSAGE/RETAIL',
  `resource_id` bigint unsigned NULL COMMENT '资源ID（包厢/房间等，后续接入 res_resource）',
  `start_at` datetime(3) NOT NULL COMMENT '预约开始时间（UTC）',
  `end_at` datetime(3) NOT NULL COMMENT '预约结束时间（UTC）',
  `party_size` int unsigned NOT NULL DEFAULT 1 COMMENT '预约人数',
  `status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CONFIRMED/ARRIVED/CANCELLED/NO_SHOW/CONVERTED',
  `order_id` bigint unsigned NULL COMMENT '到店后转统一订单ID（ord_order.id）',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人ID，0 表示系统',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_reservation_tenant_no` (`tenant_id`, `reservation_no`),
  KEY `idx_ord_reservation_store_status` (`tenant_id`, `store_id`, `status`, `start_at`),
  KEY `idx_ord_reservation_customer` (`tenant_id`, `customer_id`, `start_at`),
  KEY `idx_ord_reservation_resource` (`tenant_id`, `resource_id`, `start_at`, `status`),
  CONSTRAINT `ck_ord_reservation_party_size` CHECK (`party_size` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预约（SaaS 多租户，E-MIG）';
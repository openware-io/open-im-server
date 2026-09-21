-- 门店计价方案（KTV 首发）
CREATE TABLE `tnt_pricing_plan` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '计价方案ID',
  `tenant_id` bigint unsigned NOT NULL, `store_id` bigint unsigned NOT NULL,
  `resource_type` varchar(32) NOT NULL COMMENT '资源类型 KTV_ROOM/KTV_SERVER',
  `billing_unit` varchar(16) NOT NULL DEFAULT 'HOUR' COMMENT '计费单位 HOUR/HALF_HOUR/PACKAGE',
  `increment_minutes` int NOT NULL DEFAULT 30 COMMENT '递增粒度（分钟）',
  `rounding_direction` varchar(24) NOT NULL DEFAULT 'CONSUMER_FAVOR' COMMENT '舍入方向 CONSUMER_FAVOR/ROUND_UP/FLOOR_BLOCK',
  `price_per_unit` bigint NOT NULL COMMENT '每单位单价（最小货币单位）',
  `default_session_minutes` int NOT NULL DEFAULT 120 COMMENT '标准时长（无预订快速开台判定超时）',
  `overtime_rate` decimal(20,6) NOT NULL DEFAULT 1.0 COMMENT '超时费率',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_pricing_tenant_store_type` (`tenant_id`, `store_id`, `resource_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='门店计价方案';

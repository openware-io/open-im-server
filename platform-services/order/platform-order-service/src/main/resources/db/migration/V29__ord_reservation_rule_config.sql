-- P7-C1：预约规则由 Order 域持有；读取按门店覆盖 > 业态默认 > 租户默认。
CREATE TABLE `ord_reservation_rule_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `business_type` varchar(32) NOT NULL DEFAULT '' COMMENT '空字符串=租户默认',
  `store_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '0=租户/业态默认，大于0=门店覆盖',
  `advance_minutes` int unsigned NOT NULL DEFAULT 0 COMMENT '允许提前预约分钟数；0=不限制',
  `cancel_minutes` int unsigned NOT NULL DEFAULT 0 COMMENT '到店前最少取消分钟数；0=不限制',
  `reschedule_minutes` int unsigned NOT NULL DEFAULT 0 COMMENT '到店前最少改期分钟数；0=不限制',
  `version` int NOT NULL DEFAULT 0,
  `idempotency_key` varchar(128) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_reservation_rule_scope` (`tenant_id`,`business_type`,`store_id`),
  UNIQUE KEY `uk_ord_reservation_rule_idem` (`tenant_id`,`idempotency_key`),
  KEY `idx_ord_reservation_rule_resolve` (`tenant_id`,`business_type`,`store_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预约经营规则';

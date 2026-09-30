-- P7-C1：积分规则由 Customer 域持有；租户/业态/门店作用域由业务校验约束。
CREATE TABLE `cst_point_rule_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `business_type` varchar(32) NOT NULL DEFAULT '' COMMENT '空字符串=租户默认',
  `store_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '0=租户/业态默认，大于0=门店覆盖',
  `earn_rate` decimal(12,4) NOT NULL DEFAULT 1.0000 COMMENT '每个计价货币最小单位获得积分倍率',
  `redeem_rate` decimal(12,4) NOT NULL DEFAULT 1.0000 COMMENT '积分抵扣规则倍率',
  `expiry_days` int unsigned NOT NULL DEFAULT 0 COMMENT '0=不失效；仅租户/业态层',
  `redeem_cap_points` bigint unsigned NOT NULL DEFAULT 0 COMMENT '每笔最大抵扣积分；0=不限制',
  `version` int NOT NULL DEFAULT 0,
  `idempotency_key` varchar(128) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_point_rule_scope` (`tenant_id`,`business_type`,`store_id`),
  UNIQUE KEY `uk_cst_point_rule_idem` (`tenant_id`,`idempotency_key`),
  KEY `idx_cst_point_rule_resolve` (`tenant_id`,`business_type`,`store_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户域积分经营规则';

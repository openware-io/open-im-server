-- Payment 域日结规则配置：日结切点仅支持门店级，值为门店本地日内分钟数（0-1439）。
CREATE TABLE `pay_daily_closing_rule_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户标识',
  `store_id` bigint unsigned NOT NULL COMMENT '门店标识',
  `closing_minute` smallint unsigned NOT NULL DEFAULT 0 COMMENT '日结切点，门店本地时间距 00:00 的分钟数',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `idempotency_key` varchar(96) NULL COMMENT '配置写入幂等键',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_daily_closing_rule_scope` (`tenant_id`, `store_id`),
  UNIQUE KEY `uk_pay_daily_closing_rule_idempotency` (`tenant_id`, `idempotency_key`),
  KEY `idx_pay_daily_closing_rule_lookup` (`tenant_id`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='门店日结切点配置';

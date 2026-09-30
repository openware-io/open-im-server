-- Payment 域退款规则配置：审批阈值支持租户/业态/门店，线下退款开关支持租户/业态。
ALTER TABLE `pay_refund`
  ADD COLUMN `business_type` varchar(32) NOT NULL DEFAULT '' COMMENT '退款申请业态编码，空字符串表示未指定' AFTER `store_id`;

CREATE TABLE `pay_refund_rule_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户标识',
  `business_type` varchar(32) NOT NULL DEFAULT '' COMMENT '业态编码，空字符串表示租户默认',
  `store_id` bigint unsigned NULL COMMENT '门店标识，空值表示业态/租户默认',
  `approval_threshold` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '需要退款审批的金额阈值，使用租户币种',
  `offline_refund_enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否允许线下退款，只有租户/业态层生效',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `idempotency_key` varchar(96) NULL COMMENT '配置写入幂等键',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_refund_rule_scope` (`tenant_id`, `business_type`, `store_id`),
  UNIQUE KEY `uk_pay_refund_rule_idempotency` (`tenant_id`, `idempotency_key`),
  KEY `idx_pay_refund_rule_lookup` (`tenant_id`, `business_type`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退款审批与线下退款规则配置';

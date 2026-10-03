-- P7-C2：Order 域作废审批要求，按门店覆盖 > 业态默认 > 租户默认解析。
CREATE TABLE `ord_void_rule_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `business_type` varchar(64) NOT NULL DEFAULT '',
  `store_id` bigint NOT NULL DEFAULT 0 COMMENT '0=租户/业态默认，>0=门店覆盖',
  `require_approval` tinyint(1) NOT NULL DEFAULT 0,
  `version` int NOT NULL DEFAULT 0,
  `idempotency_key` varchar(128) NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_void_rule_scope` (`tenant_id`,`business_type`,`store_id`),
  UNIQUE KEY `uk_ord_void_rule_idem` (`tenant_id`,`idempotency_key`),
  KEY `idx_ord_void_rule_resolve` (`tenant_id`,`business_type`,`store_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单作废审批规则';

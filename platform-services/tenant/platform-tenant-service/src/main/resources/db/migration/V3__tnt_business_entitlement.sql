-- 租户业态开通：总部可见的业务组合由这里驱动。
-- 门店业务类型仍以 tnt_store.business_type 为准；本表表达“租户是否开通该业态”。
CREATE TABLE IF NOT EXISTS `tnt_business_entitlement` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `business_type` varchar(32) NOT NULL COMMENT '业态编码',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/SUSPENDED/CLOSED',
  `source` varchar(32) NOT NULL DEFAULT 'MANUAL' COMMENT '来源 STORE_DERIVED/MANUAL/SUBSCRIPTION',
  `effective_from` datetime(3) NULL COMMENT '生效时间',
  `effective_to` datetime(3) NULL COMMENT '失效时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_business_entitlement_tenant_type` (`tenant_id`, `business_type`),
  KEY `idx_tnt_business_entitlement_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户业态开通';

INSERT INTO `tnt_business_entitlement`
  (`tenant_id`, `business_type`, `status`, `source`, `effective_from`, `created_at`, `updated_at`)
SELECT DISTINCT `tenant_id`, `business_type`, 'ACTIVE', 'STORE_DERIVED', NOW(3), NOW(3), NOW(3)
FROM `tnt_store`
WHERE `business_type` IS NOT NULL AND `business_type` <> '' AND `status` = 'ACTIVE'
ON DUPLICATE KEY UPDATE `updated_at` = VALUES(`updated_at`);

INSERT INTO `tnt_business_entitlement`
  (`tenant_id`, `business_type`, `status`, `source`, `effective_from`, `created_at`, `updated_at`)
VALUES (100, 'HOTEL', 'ACTIVE', 'MANUAL', NOW(3), NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE `updated_at` = VALUES(`updated_at`);

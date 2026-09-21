-- 商户主体（法人实体）：资金/储值归属的法人公司
-- 口径依据：KTV_BUSINESS_01_SERVICE.md §8.1 / SAAS_PLATFORM_04_DATA.md（cst_wallet_account.legal_entity_id 引用本表）
CREATE TABLE `tnt_legal_entity` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '法人主体ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `name` varchar(128) NOT NULL COMMENT '法人主体名称',
  `unified_social_credit_code` varchar(32) NULL COMMENT '统一社会信用代码',
  `currency_code` char(3) NULL DEFAULT 'CNY' COMMENT '结算币种',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/SUSPENDED/CLOSED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_legal_entity_tenant_code` (`tenant_id`, `unified_social_credit_code`),
  KEY `idx_tnt_legal_entity_tenant` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商户主体(法人实体)';

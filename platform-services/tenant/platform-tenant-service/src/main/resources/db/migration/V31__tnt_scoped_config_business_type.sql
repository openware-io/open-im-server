-- P7：配置增加业态维度。
-- business_type='' 表示租户默认；store_id=0 + business_type 非空表示业态默认；
-- store_id>0 表示门店覆盖。读取顺序由领域应用服务统一实现。
ALTER TABLE `tnt_tenant_config`
  ADD COLUMN `business_type` varchar(32) NOT NULL DEFAULT '' COMMENT '业态编码，空字符串=租户默认'
    AFTER `store_id`,
  DROP KEY `uk_tnt_tenant_config_scope_key`,
  ADD UNIQUE KEY `uk_tnt_tenant_config_scope_key`
    (`tenant_id`, `business_type`, `store_id`, `config_key`),
  ADD KEY `idx_tnt_tenant_config_resolution`
    (`tenant_id`, `config_key`, `business_type`, `store_id`, `status`);

-- 配置登记只描述归属与合并策略，不提供任意 JSON 代写能力。
CREATE TABLE `tnt_config_definition` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `config_key` varchar(64) NOT NULL,
  `owner_service` varchar(64) NOT NULL,
  `value_type` varchar(24) NOT NULL,
  `scope_policy` varchar(32) NOT NULL COMMENT 'TENANT/BUSINESS/STORE',
  `merge_strategy` varchar(24) NOT NULL DEFAULT 'OVERRIDE',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_config_definition_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户配置登记表';

INSERT INTO `tnt_config_definition`
  (`config_key`, `owner_service`, `value_type`, `scope_policy`, `merge_strategy`, `status`, `created_at`, `updated_at`)
VALUES
  ('ktv_business_hours', 'platform-tenant-service', 'TIME_RANGE', 'TENANT/BUSINESS/STORE', 'OVERRIDE', 'ACTIVE', NOW(3), NOW(3)),
  ('currency', 'platform-tenant-service', 'CURRENCY', 'TENANT', 'OVERRIDE', 'ACTIVE', NOW(3), NOW(3)),
  ('wallet_brand_name', 'platform-tenant-service', 'STRING', 'TENANT', 'OVERRIDE', 'ACTIVE', NOW(3), NOW(3)),
  ('wallet_ratio', 'platform-tenant-service', 'INTEGER', 'TENANT', 'OVERRIDE', 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE
  `owner_service` = VALUES(`owner_service`),
  `value_type` = VALUES(`value_type`),
  `scope_policy` = VALUES(`scope_policy`),
  `merge_strategy` = VALUES(`merge_strategy`),
  `updated_at` = `updated_at`;

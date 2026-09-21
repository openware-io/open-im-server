-- 线上支付渠道配置（默认关）。
CREATE TABLE `pay_channel_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `tenant_id` bigint unsigned NOT NULL,
  `store_id` bigint unsigned NULL COMMENT 'NULL=租户级',
  `channel` varchar(16) NOT NULL COMMENT 'ALIPAY/WECHAT/STRIPE',
  `enabled` tinyint NOT NULL DEFAULT 0 COMMENT '0=关 1=开',
  `merchant_id` varchar(64) NULL,
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_pay_channel_tenant_store_channel` (`tenant_id`, `store_id`, `channel`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='线上支付渠道配置';

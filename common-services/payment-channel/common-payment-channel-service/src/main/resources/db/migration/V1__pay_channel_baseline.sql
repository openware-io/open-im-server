-- 支付渠道配置（微信/支付宝/Stripe，租户/门店开关，密钥加密托管占位）。
CREATE TABLE `pay_channel_provider` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `store_id` bigint unsigned NULL COMMENT 'NULL=租户级开关',
  `provider` varchar(16) NOT NULL COMMENT 'WECHAT/ALIPAY/STRIPE',
  `enabled` tinyint NOT NULL DEFAULT 0 COMMENT '0=关 1=开',
  `merchant_id` varchar(64) NULL COMMENT '渠道商户号',
  `secret_encrypted` text NULL COMMENT '渠道密钥密文占位（平台加密托管）',
  `secret_key_ref` varchar(128) NULL COMMENT '密钥托管引用占位（KMS/Vault key id）',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_channel_provider` (`tenant_id`, `store_id`, `provider`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付渠道配置（密钥加密托管占位）';

-- 渠道交易（仅记录渠道侧交易与回调原始报文，不拥有业务资金账本）。
CREATE TABLE `pay_channel_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `transaction_id` varchar(64) NOT NULL COMMENT '渠道交易号 provider transaction id',
  `provider` varchar(16) NOT NULL COMMENT 'WECHAT/ALIPAY/STRIPE',
  `order_id` varchar(64) NULL COMMENT '业务订单号',
  `amount` bigint NOT NULL COMMENT '金额最小货币单位整数（分）',
  `currency` varchar(8) NOT NULL COMMENT '币种 ISO 4217',
  `status` varchar(24) NOT NULL COMMENT 'CREATED/PROCESSING/SUCCEEDED/FAILED/CLOSED',
  `callback_raw` mediumtext NULL COMMENT '回调原始报文（受限保存，禁止输出密钥/卡号）',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_channel_txn_provider` (`provider`, `transaction_id`),
  KEY `idx_pay_channel_txn_tenant_order` (`tenant_id`, `order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付渠道交易（回调原始报文）';

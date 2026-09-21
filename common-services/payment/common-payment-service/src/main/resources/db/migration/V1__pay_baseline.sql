-- 支付与收银基线
CREATE TABLE `pay_intent` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `tenant_id` bigint unsigned NOT NULL, `store_id` bigint unsigned NOT NULL,
  `order_id` bigint unsigned NOT NULL, `merchant_account_id` bigint unsigned NULL,
  `provider` varchar(16) NOT NULL COMMENT 'CASH/WALLET/POINT/ALIPAY/WECHAT/STRIPE',
  `payment_method` varchar(32) NOT NULL, `amount` decimal(20,6) NOT NULL, `currency_code` char(3) NOT NULL,
  `status` varchar(24) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/PROCESSING/SUCCEEDED/FAILED/CLOSED',
  `idempotency_key` varchar(64) NOT NULL, `expires_at` datetime(3) NULL,
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_pay_intent_tenant_idem` (`tenant_id`, `idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付意图';

CREATE TABLE `pay_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `tenant_id` bigint unsigned NOT NULL, `payment_intent_id` bigint unsigned NOT NULL,
  `provider_transaction_no` varchar(128) NULL, `provider_payload_digest` varchar(128) NULL,
  `amount` decimal(20,6) NOT NULL, `currency_code` char(3) NOT NULL,
  `exchange_rate` decimal(20,6) NULL, `fee_amount` decimal(20,6) NULL, `status` varchar(24) NOT NULL,
  `occurred_at` datetime(3) NOT NULL, `raw_reference` varchar(255) NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_pay_transaction_provider_no` (`provider_transaction_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付交易';

CREATE TABLE `pay_refund` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `tenant_id` bigint unsigned NOT NULL, `order_id` bigint unsigned NOT NULL,
  `payment_transaction_id` bigint unsigned NULL, `request_id` varchar(64) NOT NULL,
  `requested_amount` decimal(20,6) NOT NULL, `approved_amount` decimal(20,6) NULL,
  `provider_refund_no` varchar(128) NULL, `status` varchar(24) NOT NULL DEFAULT 'REQUESTED',
  `reason` varchar(255) NULL, `requested_by` bigint unsigned NULL, `approved_by` bigint unsigned NULL,
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_pay_refund_tenant_req` (`tenant_id`, `request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退款';

CREATE TABLE `pay_shift` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `tenant_id` bigint unsigned NOT NULL, `store_id` bigint unsigned NOT NULL,
  `terminal_id` bigint unsigned NOT NULL, `operator_id` bigint unsigned NOT NULL,
  `opened_at` datetime(3) NOT NULL, `closed_at` datetime(3) NULL,
  `opening_cash` decimal(20,6) NOT NULL DEFAULT 0, `expected_cash` decimal(20,6) NOT NULL DEFAULT 0,
  `actual_cash` decimal(20,6) NULL, `difference_amount` decimal(20,6) NULL,
  `status` varchar(24) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLOSING/CLOSED/REVIEW_REQUIRED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_pay_shift_store` (`tenant_id`, `store_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='收银班次';

CREATE TABLE `pay_daily_closing` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `tenant_id` bigint unsigned NOT NULL, `store_id` bigint unsigned NOT NULL,
  `business_date` date NOT NULL, `submitted_by` bigint unsigned NULL, `reviewed_by` bigint unsigned NULL,
  `status` varchar(24) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/SUBMITTED/REVIEWED/REOPENED',
  `summary_json` json NULL,
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_pay_daily_tenant_store_date` (`tenant_id`, `store_id`, `business_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='日结';

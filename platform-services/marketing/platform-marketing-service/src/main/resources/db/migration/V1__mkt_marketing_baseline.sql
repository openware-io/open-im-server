-- 营销域基线（marketing 域，mkt_ 前缀）
-- 口径依据：SAAS_PLATFORM_04_DATA.md §9、SAAS_PLATFORM_05_API.md §8

-- 营销活动（平台活动 tenant_id 为空，租户活动带租户边界；适用范围由 mkt_campaign_scope 子表承载，首发骨架不建）
CREATE TABLE `mkt_campaign` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '营销活动ID',
  `tenant_id` bigint unsigned NULL COMMENT '租户ID，平台活动为空',
  `campaign_type` varchar(32) NOT NULL COMMENT '活动类型 COUPON/POINT/DISCOUNT',
  `name` varchar(128) NOT NULL COMMENT '活动名称',
  `start_at` datetime(3) NULL COMMENT '开始时间，空表示不限',
  `end_at` datetime(3) NULL COMMENT '结束时间，空表示不限',
  `budget_amount` bigint NOT NULL DEFAULT 0 COMMENT '活动预算(最小货币单位整数)',
  `funding_party` varchar(24) NOT NULL DEFAULT 'TENANT' COMMENT '出资方 PLATFORM/TENANT',
  `status` varchar(24) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/PAUSED/ENDED',
  `rule_snapshot_json` json NULL COMMENT '规则快照(不可查询)',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` bigint unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_mkt_campaign_status_time` (`tenant_id`, `status`, `start_at`, `end_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='营销活动';

-- 优惠券（兑换码只存摘要；金额最小货币单位整数）
CREATE TABLE `mkt_coupon` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '优惠券ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `campaign_id` bigint unsigned NULL COMMENT '关联活动ID(mkt_campaign.id)',
  `code_digest` varchar(64) NULL COMMENT '兑换码摘要(不存明文)',
  `discount_type` varchar(24) NOT NULL COMMENT 'FIXED_AMOUNT/PERCENTAGE',
  `discount_value` bigint NOT NULL COMMENT '优惠值(最小货币单位整数或万分数)',
  `min_amount` bigint NOT NULL DEFAULT 0 COMMENT '门槛金额(最小货币单位整数)',
  `max_discount` bigint NOT NULL DEFAULT 0 COMMENT '封顶金额(最小货币单位整数，0=不封顶)',
  `usage_limit` int NOT NULL DEFAULT 1 COMMENT '总使用次数限制',
  `per_customer_limit` int NOT NULL DEFAULT 1 COMMENT '每人限领数量',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED/EXPIRED',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` bigint unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_mkt_coupon_campaign` (`tenant_id`, `campaign_id`),
  KEY `idx_mkt_coupon_status` (`tenant_id`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='优惠券';

-- 优惠券发放/核销记录（发放幂等，核销不可重复）
CREATE TABLE `mkt_coupon_issuance` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '发放记录ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `coupon_id` bigint unsigned NOT NULL COMMENT '优惠券ID(mkt_coupon.id)',
  `customer_id` bigint unsigned NOT NULL COMMENT '会员档案ID(cst_member.id)',
  `issued_at` datetime(3) NULL COMMENT '发放时间',
  `used_at` datetime(3) NULL COMMENT '核销时间',
  `order_id` bigint unsigned NULL COMMENT '核销订单ID',
  `status` varchar(24) NOT NULL DEFAULT 'ISSUED' COMMENT 'ISSUED/USED/CANCELLED',
  `idempotency_key` varchar(64) NOT NULL COMMENT '幂等键',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` bigint unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mkt_coupon_issuance_customer` (`tenant_id`, `coupon_id`, `customer_id`),
  UNIQUE KEY `uk_mkt_coupon_issuance_idem` (`tenant_id`, `idempotency_key`),
  KEY `idx_mkt_coupon_issuance_coupon` (`tenant_id`, `coupon_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='优惠券发放/核销记录';

-- 营销同意（营销授权与履约通知通过 consent_type 区分，分开存储）
CREATE TABLE `mkt_consent` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '同意记录ID',
  `account_id` bigint unsigned NOT NULL COMMENT '账号ID(idt_account.id)',
  `tenant_id` bigint unsigned NULL COMMENT '租户ID，平台级同意为空',
  `consent_type` varchar(32) NOT NULL COMMENT 'MARKETING/FULFILLMENT_NOTICE',
  `channel` varchar(24) NOT NULL COMMENT 'SMS/EMAIL/APP/PUSH',
  `policy_version` varchar(32) NOT NULL DEFAULT '1.0' COMMENT '同意政策版本',
  `granted` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '是否同意 0/1',
  `source` varchar(32) NOT NULL DEFAULT 'BUSINESS' COMMENT '来源',
  `occurred_at` datetime(3) NOT NULL COMMENT '同意/变更发生时间',
  `withdrawn_at` datetime(3) NULL COMMENT '撤回时间，未撤回为空',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` bigint unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mkt_consent_scope` (`account_id`, `consent_type`, `channel`),
  KEY `idx_mkt_consent_account` (`account_id`, `consent_type`),
  CONSTRAINT `chk_mkt_consent_granted` CHECK (`granted` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='营销同意';

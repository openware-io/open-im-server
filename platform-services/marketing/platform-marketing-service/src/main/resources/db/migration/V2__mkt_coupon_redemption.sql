-- 优惠券核销记录（真实化：同一发放仅核销一次，唯一键 issuance_id 兜底 + 乐观锁 version）
-- 口径依据：SAAS_PLATFORM_05_API.md §8、SAAS_PLATFORM_06_TECHNICAL.md §8

CREATE TABLE `mkt_coupon_redemption` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '核销记录ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `issuance_id` bigint unsigned NOT NULL COMMENT '发放记录ID(mkt_coupon_issuance.id)',
  `coupon_id` bigint unsigned NOT NULL COMMENT '优惠券ID(mkt_coupon.id)',
  `customer_id` bigint unsigned NOT NULL COMMENT '会员档案ID(cst_member.id)',
  `order_id` bigint unsigned NOT NULL COMMENT '核销订单ID',
  `amount` bigint NOT NULL COMMENT '订单金额(最小货币单位整数)',
  `discount_amount` bigint NOT NULL COMMENT '核销优惠金额(最小货币单位整数)',
  `redeemed_at` datetime(3) NOT NULL COMMENT '核销时间',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` bigint unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mkt_coupon_redemption_issuance` (`issuance_id`),
  KEY `idx_mkt_coupon_redemption_order` (`tenant_id`, `order_id`),
  KEY `idx_mkt_coupon_redemption_coupon` (`tenant_id`, `coupon_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='优惠券核销记录';

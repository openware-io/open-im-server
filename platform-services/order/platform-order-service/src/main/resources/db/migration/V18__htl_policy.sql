-- 酒店店铺策略：支付模式、押金策略与取消/积分开关。
-- 币种沿用租户级 tnt_tenant_config.currency，不在酒店策略中重复存储。
CREATE TABLE `htl_policy` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `hotel_id` bigint unsigned NOT NULL COMMENT '酒店ID',
  `access_mode` varchar(24) NOT NULL DEFAULT 'SINGLE_STORE' COMMENT 'SINGLE_STORE/GROUP',
  `entry_points` varchar(255) NULL COMMENT '销售入口，逗号分隔',
  `payment_mode` varchar(24) NOT NULL DEFAULT 'PREPAY' COMMENT 'PREPAY/PAY_AT_HOTEL/DEPOSIT',
  `deposit_ratio_bps` int NOT NULL DEFAULT 3000 COMMENT '定金比例，3000=30%',
  `stay_deposit_required` tinyint(1) NOT NULL DEFAULT 1 COMMENT '入住是否必须收押金',
  `stay_deposit_mode` varchar(24) NOT NULL DEFAULT 'COLLECT' COMMENT 'COLLECT/PRE_AUTH',
  `stay_deposit_minor` bigint NOT NULL DEFAULT 0 COMMENT '固定押金（最小货币单位）',
  `stay_deposit_ratio_bps` int NOT NULL DEFAULT 0 COMMENT '按订单金额的押金比例',
  `cancel_policy` varchar(32) NOT NULL DEFAULT 'FREE_UNTIL_24H' COMMENT '取消政策',
  `points_redeem_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否支持积分抵扣',
  `points_max_ratio_bps` int NOT NULL DEFAULT 0 COMMENT '积分最大抵扣比例',
  `invoice_mode` varchar(24) NOT NULL DEFAULT 'NONE' COMMENT '预留，当前不接发票',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_htl_policy_tenant_store_hotel` (`tenant_id`, `store_id`, `hotel_id`),
  KEY `idx_htl_policy_store` (`tenant_id`, `store_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='酒店店铺策略';

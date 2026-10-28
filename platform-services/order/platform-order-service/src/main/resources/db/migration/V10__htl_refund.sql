-- 酒店退款申请：现金立即记账，线上保持 PENDING 等 payment-service 审批/打款。
CREATE TABLE IF NOT EXISTS `htl_refund` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `store_id` bigint unsigned NOT NULL,
  `booking_id` bigint unsigned NOT NULL,
  `folio_id` bigint unsigned NOT NULL,
  `order_id` bigint unsigned NULL,
  `amount_minor` bigint NOT NULL,
  `currency` char(3) NOT NULL,
  `method` varchar(16) NOT NULL,
  `status` varchar(24) NOT NULL,
  `external_ref` varchar(128) NULL,
  `reason` varchar(255) NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_htl_refund_booking` (`booking_id`),
  KEY `idx_htl_refund_status` (`tenant_id`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='酒店退款申请';

-- 组合收款幂等表（对齐 SAAS_PLATFORM_05 §7 / SAAS_PLATFORM_06 §7.2/§7.3）
-- 入口先按 idempotency_key 查 pay_collect，命中直接返回首次 response_json（整体幂等返回首次结果）。
CREATE TABLE `pay_collect` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `collect_no` varchar(64) NOT NULL COMMENT '组合收款单号',
  `order_id` bigint unsigned NOT NULL COMMENT '订单ID',
  `idempotency_key` varchar(64) NOT NULL COMMENT '幂等键(Idempotency-Key)',
  `state` varchar(24) NOT NULL DEFAULT 'INIT' COMMENT 'INIT/HOLD/CONFIRMED/FAILED',
  `request_json` json NULL COMMENT '首次请求快照',
  `response_json` json NULL COMMENT '首次响应快照(幂等重放返回)',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_collect_idem` (`tenant_id`, `idempotency_key`),
  KEY `idx_pay_collect_tenant_order` (`tenant_id`, `order_id`),
  KEY `idx_pay_collect_tenant_no` (`tenant_id`, `collect_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='组合收款幂等';

-- 渠道回调幂等结算台账（payment 域）。
-- Domain: payment; applies only to a new database without executed baseline.
--
-- 为什么需要这张表：渠道回调可能重复/乱序投递，而 pay_intent 的幂等键是"租户内分腿唯一"
-- （组合收款按分腿序号派生），不天然表达"同一渠道交易只结算一次"。结算台账以
-- (provider, transaction_id, kind) 唯一约束作为渠道回调的幂等判据：
--   * 收款成功回调（kind=PAYMENT）命中 → 回放，不重复落 pay_intent/pay_transaction，也不二次 markPaid；
--   * 退款成功回调（kind=REFUND）命中 → 回放，不重复回冲 ord_order.paid_amount/refund_status。
-- 失败/关闭回调不写本表（否则会挡住后续真实成功回调），因此不影响状态机推进。

CREATE TABLE `pay_channel_settlement` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID（以订单口径为准）',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID（以订单口径为准）',
  `order_id` bigint unsigned NOT NULL COMMENT '业务订单ID（渠道 out_trade_no）',
  `provider` varchar(16) NOT NULL COMMENT '渠道：ALIPAY/WECHAT/STRIPE（大写归一）',
  `transaction_id` varchar(64) NOT NULL COMMENT '渠道交易号（幂等键）',
  `event_id` varchar(64) NULL COMMENT '渠道回调事件ID（Stripe event id/微信v3通知id/支付宝notify_id，追溯用）',
  `kind` varchar(16) NOT NULL COMMENT '结算类型：PAYMENT/REFUND',
  `amount` decimal(20,6) NOT NULL COMMENT '本次结算金额（最小货币单位整数）',
  `currency_code` char(3) NOT NULL COMMENT '币种快照（必须等于订单币种）',
  `status` varchar(24) NOT NULL COMMENT '结算结果：SUCCEEDED/REFUNDED',
  `pay_intent_id` bigint unsigned NULL COMMENT '本次落账的支付意图ID（收款成功才有）',
  `pay_transaction_id` bigint unsigned NULL COMMENT '本次落账的支付交易ID（收款成功才有）',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_channel_settlement_provider_transaction_kind` (`provider`, `transaction_id`, `kind`),
  KEY `idx_pay_channel_settlement_tenant_order` (`tenant_id`, `order_id`),
  KEY `idx_pay_channel_settlement_provider_event` (`provider`, `event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='渠道回调幂等结算台账';

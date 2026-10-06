-- 支付事实继承订单创建时的业态快照。
-- 历史行允许为空；新写入必须由已签名上下文/订单快照传播。
ALTER TABLE `pay_collect`
  ADD COLUMN `business_type` varchar(32) NULL COMMENT '收款发生业态快照' AFTER `store_id`,
  ADD KEY `idx_pay_collect_tenant_store_type_created` (`tenant_id`, `store_id`, `business_type`, `created_at`);

ALTER TABLE `pay_transaction`
  ADD COLUMN `business_type` varchar(32) NULL COMMENT '交易发生业态快照' AFTER `store_id`,
  ADD KEY `idx_pay_transaction_tenant_store_type_occurred` (`tenant_id`, `store_id`, `business_type`, `occurred_at`);

-- 支付事实门店归因（P4C）。
--
-- pay_intent 已有 store_id；本迁移补齐组合收款、交易、退款三张事实表，
-- 使总部汇总与门店下钻不必依赖跨域订单 JOIN 推断。
-- 历史数据可能无法可靠推导门店，列先允许 NULL；新写路径均写入上下文 store_id，
-- 待独立回填校验批次完成后再由后续迁移收紧 NOT NULL。
ALTER TABLE `pay_collect`
  ADD COLUMN `store_id` bigint unsigned NULL COMMENT '收款发生门店；历史不可判定时为空' AFTER `tenant_id`,
  ADD KEY `idx_pay_collect_tenant_store_created` (`tenant_id`, `store_id`, `created_at`);

ALTER TABLE `pay_transaction`
  ADD COLUMN `store_id` bigint unsigned NULL COMMENT '交易发生门店；与支付意图上下文一致' AFTER `tenant_id`,
  ADD KEY `idx_pay_transaction_tenant_store_occurred` (`tenant_id`, `store_id`, `occurred_at`);

ALTER TABLE `pay_refund`
  ADD COLUMN `store_id` bigint unsigned NULL COMMENT '退款申请所属门店；历史不可判定时为空' AFTER `tenant_id`,
  ADD KEY `idx_pay_refund_tenant_store_created` (`tenant_id`, `store_id`, `created_at`);

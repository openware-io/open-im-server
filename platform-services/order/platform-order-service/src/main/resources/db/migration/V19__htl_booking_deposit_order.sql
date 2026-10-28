-- 定金单独建收款单，避免共享支付服务把“部分收款”误当全额订单。
ALTER TABLE `htl_booking`
  ADD COLUMN `deposit_order_id` bigint unsigned NULL COMMENT '定金收款单ID' AFTER `order_id`,
  ADD COLUMN `deposit_amount_minor` bigint NOT NULL DEFAULT 0 COMMENT '定金金额（最小货币单位）' AFTER `deposit_order_id`;

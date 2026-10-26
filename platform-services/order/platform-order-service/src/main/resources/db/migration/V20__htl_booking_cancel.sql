-- 取消政策结果：记录取消时计算出的费用与应退金额，退款动作仍沿用既有支付/退款能力。
ALTER TABLE `htl_booking`
    ADD COLUMN `cancel_fee_minor` bigint NOT NULL DEFAULT 0 COMMENT '取消费用（最小货币单位）' AFTER `currency`,
    ADD COLUMN `refund_minor` bigint NOT NULL DEFAULT 0 COMMENT '应退金额（最小货币单位）' AFTER `cancel_fee_minor`,
    ADD COLUMN `cancelled_at` datetime(3) NULL COMMENT '取消时间' AFTER `refund_minor`;

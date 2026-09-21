-- 交易表冗余支付渠道，用于对账按渠道汇总。
ALTER TABLE `pay_transaction` ADD COLUMN `provider` varchar(16) NULL COMMENT '支付渠道' AFTER `payment_intent_id`;

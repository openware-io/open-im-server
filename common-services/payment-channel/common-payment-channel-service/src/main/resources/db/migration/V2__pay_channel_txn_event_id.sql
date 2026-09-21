-- 回调事件幂等去重：Stripe event id / 微信 v3 通知 id / 支付宝 notify_id，重放防护。
ALTER TABLE `pay_channel_transaction`
  ADD COLUMN `callback_event_id` varchar(64) NULL COMMENT '回调事件 ID（重放去重）' AFTER `transaction_id`,
  ADD UNIQUE KEY `uk_pay_channel_txn_event` (`provider`, `callback_event_id`);

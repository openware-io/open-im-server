-- 渠道回调结算状态（渠道回调 → payment-service 内部结算接口的调用结果）。
-- Domain: payment-channel; applies only to an existing database that already executed V1.
--
-- 为什么需要这些列：验签成功不代表已落账。此前渠道回调在写完 pay_channel_transaction 后不回写支付/订单域，
-- 回调"看起来成功"而订单永远未收款。二期由结算桥调用 payment-service 内部接口，调用结果必须可观测、可重试：
--   * settlement_status：PENDING（仅验签落渠道交易，尚未结算成功）/ APPLIED / FAILED；
--   * settlement_attempts：调用尝试次数（重复回调/渠道重投累加）；
--   * settlement_error：最近一次失败原因（截断，不含密钥与完整报文）；
--   * settlement_updated_at：最近一次调用时间。
-- 索引沿用既有 uk_pay_channel_transaction_provider_transaction 唯一定位（按 provider + transaction_id 更新），无需新增索引。

ALTER TABLE `pay_channel_transaction`
  ADD COLUMN `settlement_status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT '回调结算状态：PENDING/APPLIED/FAILED',
  ADD COLUMN `settlement_attempts` int NOT NULL DEFAULT 0 COMMENT '结算调用尝试次数（失败重投累加）',
  ADD COLUMN `settlement_error` varchar(255) NULL COMMENT '最近一次结算失败原因（截断，不含密钥/完整报文）',
  ADD COLUMN `settlement_updated_at` datetime(3) NULL COMMENT '最近一次结算调用时间';

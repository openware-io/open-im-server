-- P7-B：支付渠道配置增加业态维度、版本和幂等键。
ALTER TABLE `pay_channel_config`
  ADD COLUMN `business_type` varchar(32) NOT NULL DEFAULT '' COMMENT '业态编码，空字符串=租户默认' AFTER `store_id`,
  ADD COLUMN `version` int NOT NULL DEFAULT 0 AFTER `status`,
  ADD COLUMN `idempotency_key` varchar(96) NULL AFTER `version`;
ALTER TABLE `pay_channel_config`
  DROP KEY `uk_pay_channel_tenant_store_channel`,
  ADD UNIQUE KEY `uk_pay_channel_tenant_store_type` (`tenant_id`, `business_type`, `store_id`, `channel`),
  ADD UNIQUE KEY `uk_pay_channel_idempotency` (`tenant_id`, `idempotency_key`, `channel`);

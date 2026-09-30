-- P7-C1：积分有效期按获得流水记录，不拆分租户共享积分账户。
ALTER TABLE `cst_point_ledger`
  ADD COLUMN `expires_at` datetime(3) NULL COMMENT 'EARN 流水失效时间，NULL=不失效' AFTER `rule_snapshot_json`,
  ADD KEY `idx_cst_point_ledger_expiry` (`tenant_id`,`account_id`,`expires_at`,`entry_type`);

-- P7-B：计价方案三层作用域已在 V2 基线建模；本迁移为已有安装补齐版本和幂等列。
-- 新安装由 V2 直接创建完整结构，老安装执行本迁移。
ALTER TABLE `tnt_pricing_plan`
  ADD COLUMN `business_type` varchar(32) NOT NULL DEFAULT '' COMMENT '业态编码，空字符串=租户默认' AFTER `store_id`,
  ADD COLUMN `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本' AFTER `status`,
  ADD COLUMN `idempotency_key` varchar(96) DEFAULT NULL COMMENT '写入幂等键' AFTER `version`;

ALTER TABLE `tnt_pricing_plan`
  DROP KEY `uk_tnt_pricing_tenant_store_type`,
  ADD UNIQUE KEY `uk_tnt_pricing_scope_type` (`tenant_id`, `business_type`, `store_id`, `resource_type`),
  ADD UNIQUE KEY `uk_tnt_pricing_idempotency` (`tenant_id`, `idempotency_key`, `resource_type`);

-- P5A/P5C：客户首次建档门店与积分/储值流水门店归因。
-- 账户仍是租户级共享资产，禁止把 store_id 加到账户表；历史无法可靠推导的流水保留 NULL。
ALTER TABLE `cst_member`
  ADD COLUMN `origin_store_id` bigint unsigned NULL COMMENT '首次新增客户的门店；总部创建或历史不可判定时为空' AFTER `tenant_id`,
  ADD KEY `idx_cst_member_tenant_origin_store` (`tenant_id`, `origin_store_id`, `created_at`);

ALTER TABLE `cst_wallet_ledger`
  ADD COLUMN `store_id` bigint unsigned NULL COMMENT '本笔储值业务发生门店；历史不可判定时为空' AFTER `tenant_id`,
  ADD KEY `idx_cst_wallet_ledger_tenant_store_created` (`tenant_id`, `store_id`, `created_at`);

ALTER TABLE `cst_point_ledger`
  ADD COLUMN `store_id` bigint unsigned NULL COMMENT '本笔积分业务发生门店；历史不可判定时为空' AFTER `tenant_id`,
  ADD KEY `idx_cst_point_ledger_tenant_store_created` (`tenant_id`, `store_id`, `created_at`);

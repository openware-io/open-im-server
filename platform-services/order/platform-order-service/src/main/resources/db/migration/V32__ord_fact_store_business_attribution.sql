-- 订单派生事实继承订单创建时的门店与业态快照。
-- 允许历史行为空；新写入必须由应用层从订单快照传播，禁止请求体覆盖。
ALTER TABLE `ord_order_item`
  ADD COLUMN `store_id` bigint unsigned NULL COMMENT '订单发生门店快照' AFTER `tenant_id`,
  ADD COLUMN `business_type` varchar(32) NULL COMMENT '订单业态快照' AFTER `store_id`,
  ADD KEY `idx_ord_order_item_tenant_store_type` (`tenant_id`, `store_id`, `business_type`, `created_at`);

ALTER TABLE `ord_ktv_session`
  ADD COLUMN `store_id` bigint unsigned NULL COMMENT '订单发生门店快照' AFTER `tenant_id`,
  ADD COLUMN `business_type` varchar(32) NULL COMMENT '订单业态快照' AFTER `store_id`,
  ADD KEY `idx_ord_ktv_session_store_type` (`tenant_id`, `store_id`, `business_type`, `status`);

ALTER TABLE `ord_ktv_server_session`
  ADD COLUMN `store_id` bigint unsigned NULL COMMENT '订单发生门店快照' AFTER `tenant_id`,
  ADD COLUMN `business_type` varchar(32) NULL COMMENT '订单业态快照' AFTER `store_id`,
  ADD KEY `idx_ord_ktv_server_store_type` (`tenant_id`, `store_id`, `business_type`, `status`);

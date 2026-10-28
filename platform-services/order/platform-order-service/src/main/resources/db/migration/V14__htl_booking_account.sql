-- S5 C 端订单归属：C 下单写入 account，列表按本人过滤。
ALTER TABLE `htl_booking`
  ADD COLUMN `booked_by_account_id` bigint unsigned NULL COMMENT 'C 端下单账号ID；B 代客下单为空' AFTER `order_id`,
  ADD KEY `idx_htl_booking_store_account` (`store_id`, `booked_by_account_id`);

-- H4 支付桥接：酒店预订关联订单应收。
ALTER TABLE `htl_booking`
  ADD COLUMN `order_id` bigint unsigned NULL AFTER `rate_plan_id`,
  ADD KEY `idx_htl_booking_order` (`order_id`);

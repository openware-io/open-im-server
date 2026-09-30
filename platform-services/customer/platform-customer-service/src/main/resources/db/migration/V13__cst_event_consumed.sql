-- Customer 消费支付收款确认事件的幂等记录；仅在积分获得成功后写入。
CREATE TABLE `cst_event_consumed` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `event_id` varchar(64) NOT NULL,
  `event_type` varchar(64) NOT NULL,
  `aggregate_type` varchar(32) NOT NULL,
  `aggregate_id` varchar(64) NOT NULL,
  `consumed_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_event_consumed_event` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Customer事件消费幂等记录';

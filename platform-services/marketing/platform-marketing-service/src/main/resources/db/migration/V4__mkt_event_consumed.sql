-- 营销事件消费去重表：event_id 唯一键兜底消费幂等（SAAS_PLATFORM_06_TECHNICAL.md §8）
-- Relay 以 eventId 为幂等键至少一次投递，消费者以此表去重，重复消费直接跳过。

CREATE TABLE `mkt_event_consumed` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '去重记录ID',
  `tenant_id` bigint unsigned NULL COMMENT '租户ID，平台级事件为空',
  `event_id` varchar(64) NOT NULL COMMENT '事件ID(消费幂等键)',
  `event_type` varchar(64) NOT NULL COMMENT '事件类型(如 coupon.redeemed)',
  `aggregate_type` varchar(32) NOT NULL COMMENT '聚合类型(如 coupon)',
  `aggregate_id` varchar(64) NOT NULL COMMENT '聚合ID',
  `consumed_at` datetime(3) NOT NULL COMMENT '消费时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mkt_event_consumed_event` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='营销事件消费去重表';

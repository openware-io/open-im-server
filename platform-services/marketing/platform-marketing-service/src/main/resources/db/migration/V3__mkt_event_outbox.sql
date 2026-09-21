-- 营销事件 Outbox（占位：业务事务与 Outbox 同库提交，Relay 按 PENDING→PUBLISHED/FAILED 投递 RocketMQ）
-- 口径依据：SAAS_PLATFORM_06_TECHNICAL.md §8；事件载荷最小化，带租户上下文，不含敏感原文

CREATE TABLE `mkt_event_outbox` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'Outbox记录ID',
  `tenant_id` bigint unsigned NULL COMMENT '租户ID，平台级事件为空',
  `event_id` varchar(64) NOT NULL COMMENT '事件ID(全局唯一，消费幂等)',
  `event_type` varchar(64) NOT NULL COMMENT '事件类型(如 coupon.redeemed)',
  `aggregate_type` varchar(32) NOT NULL COMMENT '聚合类型(如 coupon)',
  `aggregate_id` varchar(64) NOT NULL COMMENT '聚合ID',
  `payload_json` json NOT NULL COMMENT '事件载荷(最小化，含租户上下文)',
  `status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PUBLISHED/FAILED',
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '已重试次数',
  `next_retry_at` datetime(3) NULL COMMENT '下次重试时间(指数退避)',
  `published_at` datetime(3) NULL COMMENT '投递成功时间',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mkt_event_outbox_event` (`event_id`),
  KEY `idx_mkt_event_outbox_pending` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='营销事件 Outbox';

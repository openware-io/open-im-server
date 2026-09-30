-- Customer 事实事件 Outbox：业务记账与事件行在同一个本地事务中提交，由 Relay 可靠投递。
CREATE TABLE `cst_event_outbox` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'Outbox记录ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `event_id` varchar(64) NOT NULL COMMENT '事件ID(全局唯一，消费幂等)',
  `event_type` varchar(64) NOT NULL COMMENT '客户资产事实类型',
  `aggregate_type` varchar(32) NOT NULL COMMENT '聚合类型',
  `aggregate_id` varchar(64) NOT NULL COMMENT '聚合ID',
  `payload_json` json NOT NULL COMMENT '最小化事实载荷，不含PII',
  `status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PUBLISHED/FAILED',
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '已重试次数',
  `next_retry_at` datetime(3) NULL COMMENT '下次重试时间',
  `published_at` datetime(3) NULL COMMENT '投递成功时间',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_event_outbox_event` (`event_id`),
  KEY `idx_cst_event_outbox_pending` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Customer事实事件Outbox';

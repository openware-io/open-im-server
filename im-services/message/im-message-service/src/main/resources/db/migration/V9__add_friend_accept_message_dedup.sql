-- 好友通过自动私聊消息的持久化幂等记录；消息硬删除后仍保留，防止旧事件重放。
CREATE TABLE `msg_friend_accept_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `request_id` bigint NOT NULL COMMENT '好友申请 ID',
  `event_id` varchar(128) NOT NULL COMMENT '好友通过事件 ID',
  `status` varchar(16) NOT NULL COMMENT '处理状态：PENDING/SUCCEEDED/SKIPPED',
  `msg_id` varchar(128) NULL COMMENT '生成的正式消息 ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间（UTC）',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间（UTC）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_friend_accept_request` (`request_id`),
  UNIQUE KEY `uk_msg_friend_accept_event` (`event_id`),
  KEY `idx_msg_friend_accept_status_updated` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='好友通过自动私聊消息幂等记录';

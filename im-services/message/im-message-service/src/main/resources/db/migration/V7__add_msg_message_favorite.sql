-- 用户消息收藏（引用消息方案）：仅存 msgId + 会话定位信息，列表时回查权威消息表。
-- 消息被撤回/删除（权威消息硬删除）时回查不到，列表侧渲染占位，不复制正文快照。

CREATE TABLE `msg_message_favorite`
(
  `id`         bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`    bigint NOT NULL COMMENT '收藏用户 ID',
  `msg_id`     varchar(64) NOT NULL COMMENT '被收藏消息全局标识（引用 msg_message.msg_id，不做外键约束）',
  `peer_id`    varchar(64) NOT NULL COMMENT '消息所属会话/对端标识（定位信息，用于回查消息）',
  `chat_type`  varchar(32) NOT NULL COMMENT '聊天类型，例如 private 或 group',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '收藏时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_message_favorite_user_msg` (`user_id`, `msg_id`) COMMENT '同一用户重复收藏幂等约束',
  KEY `idx_msg_message_favorite_user_created` (`user_id`, `created_at`) COMMENT '用户收藏列表按收藏时间倒序分页'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户消息收藏（引用消息，正文回查权威消息表）';

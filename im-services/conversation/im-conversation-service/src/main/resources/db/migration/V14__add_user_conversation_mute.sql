CREATE TABLE `user_conversation_mute`
(
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`         bigint unsigned NOT NULL COMMENT '用户 ID',
  `conversation_id` varchar(128) NOT NULL COMMENT '会话标识（conv:private/group/channel 或 secret/secret_group）',
  `created_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_conversation_mute` (`user_id`, `conversation_id`),
  KEY `idx_user_conversation_mute_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户会话免打扰';

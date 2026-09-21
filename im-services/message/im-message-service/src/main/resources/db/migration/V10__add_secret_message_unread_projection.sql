CREATE TABLE `msg_secret_unread`
(
  `chat_type`       varchar(32) NOT NULL COMMENT 'secret 或 secret_group',
  `conversation_id` bigint NOT NULL COMMENT '私密会话或私密群聊标识',
  `user_id`         bigint NOT NULL COMMENT '接收方用户标识',
  `msg_id`          varchar(64) NOT NULL COMMENT '消息标识（不保存密文）',
  `seq`             bigint NOT NULL COMMENT '会话内序号',
  `created_at`      datetime(3) NOT NULL COMMENT '投影创建时间',
  PRIMARY KEY (`chat_type`, `user_id`, `msg_id`),
  KEY `idx_secret_unread_user_conversation_seq` (`user_id`, `chat_type`, `conversation_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='私密消息未读投影（不含内容，用于角标、跨设备同步与离线推送）';

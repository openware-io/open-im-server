-- 私密聊天密文消息权威表（E2EE：服务端仅存储密文，不解密；定时销毁由 destroy_at 驱动）

CREATE TABLE `msg_secret_message`
(
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '密文消息标识',
  `secret_chat_id`  bigint unsigned NOT NULL COMMENT '私密会话标识',
  `msg_id`          varchar(64) NOT NULL COMMENT '业务消息标识',
  `from_user_id`    bigint unsigned NOT NULL COMMENT '发送者用户标识',
  `ciphertext`      text NOT NULL COMMENT '客户端加密密文（服务端不解密）',
  `seq`             bigint NOT NULL COMMENT '会话内递增序号',
  `status`          varchar(32) NOT NULL DEFAULT 'active' COMMENT '消息状态',
  `destroy_at`      datetime(3) DEFAULT NULL COMMENT '定时销毁时间（已读后计时）',
  `created_by`      bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at`      datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by`      bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at`      datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_secret_message_msg_id` (`msg_id`) COMMENT '消息幂等唯一约束',
  KEY `idx_msg_secret_message_chat_seq` (`secret_chat_id`, `seq`) COMMENT '按会话游标拉取',
  KEY `idx_msg_secret_message_destroy_at` (`destroy_at`) COMMENT '定时销毁扫描'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私密聊天密文消息权威表';

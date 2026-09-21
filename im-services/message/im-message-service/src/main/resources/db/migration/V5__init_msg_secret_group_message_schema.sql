-- 私密群聊消息域基线：按成员分别存密文（逐成员加密方案 A）+ 销毁痕迹

CREATE TABLE `msg_secret_group_message`
(
  `id`                 bigint NOT NULL AUTO_INCREMENT COMMENT '消息标识',
  `secret_group_id`    bigint NOT NULL COMMENT '私密群聊标识',
  `msg_id`             varchar(64) NOT NULL COMMENT '客户端消息标识（幂等）',
  `from_user_id`       bigint NOT NULL COMMENT '发送方用户标识',
  `recipient_user_id`  bigint NOT NULL COMMENT '接收方用户标识（每成员一份密文）',
  `ciphertext`         text NOT NULL COMMENT '密文（服务端不解密）',
  `seq`                bigint NOT NULL COMMENT '群内单调递增序号（游标同步）',
  `status`             varchar(32) NOT NULL DEFAULT 'active' COMMENT '状态(active/destroyed/recalled)',
  `destroy_at`         datetime(3) DEFAULT NULL COMMENT '定时销毁时间',
  `created_at`         datetime(3) NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_secret_group_msg` (`secret_group_id`, `msg_id`, `recipient_user_id`),
  KEY `idx_secret_group_msg_cursor` (`secret_group_id`, `recipient_user_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私密群聊消息权威表（逐成员密文）';

CREATE TABLE `msg_secret_group_message_destroyed`
(
  `id`              bigint NOT NULL AUTO_INCREMENT COMMENT '销毁痕迹标识',
  `secret_group_id` bigint NOT NULL COMMENT '私密群聊标识',
  `msg_id`          varchar(64) NOT NULL COMMENT '消息标识',
  `destroy_at`      datetime(3) NOT NULL COMMENT '销毁时刻',
  `reason`          varchar(32) NOT NULL COMMENT '销毁原因(destroyed/recalled/deleted)',
  PRIMARY KEY (`id`),
  KEY `idx_secret_group_destroyed_sync` (`secret_group_id`, `destroy_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私密群聊消息销毁痕迹表（增量同步）';

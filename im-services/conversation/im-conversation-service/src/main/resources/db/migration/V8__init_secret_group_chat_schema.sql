-- 私密群聊（E2EE 群聊）会话域基线：群聚合 + 成员设备公钥（逐成员加密方案 A）

CREATE TABLE `conversation_secret_group_chat`
(
  `id`             bigint NOT NULL AUTO_INCREMENT COMMENT '私密群聊标识',
  `owner_user_id`  bigint NOT NULL COMMENT '群主用户标识',
  `status`         varchar(32) NOT NULL DEFAULT 'active' COMMENT '状态(active/closed)',
  `safe_code`      varchar(128) DEFAULT NULL COMMENT '群安全码指纹（排序后成员公钥 SHA-256）',
  `destroy_policy` varchar(32) NOT NULL DEFAULT 'off' COMMENT '定时销毁策略(off/30s/5m/1h/1d)',
  `created_by`     bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at`     datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by`     bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at`     datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_secret_group_chat_owner` (`owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私密群聊会话权威表';

CREATE TABLE `conversation_secret_group_member`
(
  `id`                bigint NOT NULL AUTO_INCREMENT COMMENT '成员标识',
  `secret_group_id`   bigint NOT NULL COMMENT '私密群聊标识',
  `user_id`           bigint NOT NULL COMMENT '成员用户标识',
  `device_public_key` varchar(512) DEFAULT NULL COMMENT '成员设备公钥（客户端生成，服务端仅存储转发）',
  `joined_at`         datetime(3) NOT NULL COMMENT '加入时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_secret_group_member` (`secret_group_id`, `user_id`),
  KEY `idx_secret_group_member_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私密群聊成员权威表';

-- 私密聊天会话域数据库基线（形态先行：会话实体 + 安全码 + 销毁策略，不含密文存储）

CREATE TABLE `secret_chat`
(
  `id`                    bigint NOT NULL AUTO_INCREMENT COMMENT '私密会话标识',
  `user_a`                bigint NOT NULL COMMENT '参与者 A（规范化较小用户）',
  `user_b`                bigint NOT NULL COMMENT '参与者 B（规范化较大用户）',
  `status`                varchar(32) NOT NULL COMMENT '会话状态（handshake/ready/closed）',
  `safe_code`             varchar(128) DEFAULT NULL COMMENT '安全码指纹（形态先行展示用）',
  `destroy_policy`        varchar(32) NOT NULL DEFAULT 'off' COMMENT '定时销毁策略',
  `created_at`            datetime(3) NOT NULL COMMENT '创建时间',
  `updated_at`            datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_secret_chat_users` (`user_a`, `user_b`) COMMENT '一对用户唯一私密会话',
  KEY `idx_secret_chat_user_b` (`user_b`) COMMENT '按参与者 B 查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私密聊天会话权威表';

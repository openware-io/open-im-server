-- 会话域数据库基线：仅适用于尚未执行此基线的新建数据库。

CREATE TABLE `conversation_group`
(
  `id`                    bigint NOT NULL AUTO_INCREMENT COMMENT '群组标识',
  `name`                  varchar(128) NOT NULL COMMENT '群名称',
  `avatar`                varchar(512) DEFAULT NULL COMMENT '群头像地址',
  `owner_id`              bigint NOT NULL COMMENT '群主用户标识',
  `announcement`          text COMMENT '群公告',
  `max_members`           int NOT NULL DEFAULT 500 COMMENT '最大成员数',
  `status`                varchar(32) NOT NULL COMMENT '群状态',
  `authorization_version` bigint NOT NULL DEFAULT 1 COMMENT '授权版本',
  `created_at`            datetime(3) NOT NULL COMMENT '创建时间',
  `updated_at`            datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_conversation_group_owner_id` (`owner_id`) COMMENT '群主查询',
  KEY `idx_conversation_group_created_at` (`created_at`) COMMENT '按时间查询群组'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话域群组权威表';

CREATE TABLE `conversation_group_member`
(
  `id`                    bigint NOT NULL AUTO_INCREMENT COMMENT '成员标识',
  `group_id`              bigint NOT NULL COMMENT '群组标识',
  `user_id`               bigint NOT NULL COMMENT '用户标识',
  `role`                  varchar(32) NOT NULL COMMENT '群成员角色',
  `nickname`              varchar(128) DEFAULT NULL COMMENT '群昵称',
  `is_muted`              bit NOT NULL DEFAULT b'0' COMMENT '是否禁言',
  `muted_until`           datetime(3) DEFAULT NULL COMMENT '禁言截止时间',
  `authorization_version` bigint NOT NULL DEFAULT 1 COMMENT '授权版本',
  `joined_at`             datetime(3) NOT NULL COMMENT '加入时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_group_member_group_user` (`group_id`, `user_id`) COMMENT '群成员唯一约束',
  KEY `idx_conversation_group_member_user_id` (`user_id`) COMMENT '用户群组查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话域群成员权威表';

CREATE TABLE `conversation_outbox`
(
  `id`           bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_id`     varchar(64) NOT NULL COMMENT '事件标识',
  `aggregate_id` varchar(64) NOT NULL COMMENT '聚合标识',
  `topic`        varchar(128) NOT NULL COMMENT '消息主题',
  `sharding_key` varchar(128) NOT NULL COMMENT '顺序分片键',
  `payload_json` json NOT NULL COMMENT '事件载荷',
  `published`    bit NOT NULL DEFAULT b'0' COMMENT '是否已发布',
  `created_at`   datetime(3) NOT NULL COMMENT '创建时间',
  `published_at` datetime(3) DEFAULT NULL COMMENT '发布时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_outbox_event_id` (`event_id`) COMMENT '事件幂等约束',
  KEY `idx_conversation_outbox_pending` (`published`, `id`) COMMENT '待发布事件扫描'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话域授权事件待发布表';

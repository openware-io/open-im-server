-- 频道域数据库基线：单向发布/订阅模型

CREATE TABLE `channel`
(
  `id`                    bigint NOT NULL AUTO_INCREMENT COMMENT '频道标识',
  `owner_id`              bigint NOT NULL COMMENT '所有者用户标识',
  `name`                  varchar(128) NOT NULL COMMENT '频道名称',
  `avatar`                varchar(512) DEFAULT NULL COMMENT '频道头像地址',
  `announcement`          text COMMENT '频道公告',
  `discussion_group_id`   bigint DEFAULT NULL COMMENT '关联讨论群组标识',
  `status`                varchar(32) NOT NULL COMMENT '频道状态',
  `created_at`            datetime(3) NOT NULL COMMENT '创建时间',
  `updated_at`            datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_channel_owner_id` (`owner_id`) COMMENT '所有者查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道权威表';

CREATE TABLE `channel_subscription`
(
  `id`                    bigint NOT NULL AUTO_INCREMENT COMMENT '订阅标识',
  `channel_id`            bigint NOT NULL COMMENT '频道标识',
  `user_id`               bigint NOT NULL COMMENT '订阅用户标识',
  `notify_setting`        varchar(32) NOT NULL DEFAULT 'default' COMMENT '通知设置',
  `joined_at`             datetime(3) NOT NULL COMMENT '订阅时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_subscription_channel_user` (`channel_id`, `user_id`) COMMENT '订阅唯一约束',
  KEY `idx_channel_subscription_user_id` (`user_id`) COMMENT '用户订阅查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道订阅权威表';

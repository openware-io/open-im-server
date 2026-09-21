-- 消息域数据库基线：仅适用于尚未执行此基线的新建数据库。

CREATE TABLE `msg_message`
(
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `msg_id`          varchar(64) NOT NULL COMMENT '消息全局标识，也是消息写入幂等键',
  `conversation_id` varchar(128) NOT NULL COMMENT '会话标识，用于消息顺序与查询',
  `seq`             bigint NOT NULL COMMENT '会话内单调递增序号',
  `from_user_id`    bigint NOT NULL COMMENT '发送用户 ID',
  `sender_username` varchar(128) NOT NULL DEFAULT '' COMMENT '发送用户名快照',
  `to_id`           varchar(64) NOT NULL COMMENT '接收对象标识',
  `chat_type`       varchar(32) NOT NULL COMMENT '聊天类型，例如 private 或 group',
  `msg_type`        varchar(32) NOT NULL COMMENT '消息类型，例如 text 或 image',
  `content`         text NOT NULL COMMENT '消息正文',
  `client_msg_id`   varchar(128) DEFAULT NULL COMMENT '客户端消息标识，用于客户端重试去重',
  `reply_msg_id`    varchar(64) DEFAULT NULL COMMENT '被回复消息标识',
  `at_users`        json DEFAULT NULL COMMENT '@用户标识列表',
  `media_object_ids` json DEFAULT NULL COMMENT '受管媒体对象标识列表',
  `status`          varchar(32) NOT NULL COMMENT '消息状态',
  `created_by`      bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`      bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_message_msg_id` (`msg_id`) COMMENT '消息全局幂等约束',
  UNIQUE KEY `uk_msg_message_sender_client` (`from_user_id`, `client_msg_id`) COMMENT '客户端重试幂等约束',
  UNIQUE KEY `uk_msg_message_conversation_seq` (`conversation_id`, `seq`) COMMENT '会话内消息顺序唯一约束',
  KEY `idx_msg_message_conversation_seq` (`conversation_id`, `seq`) COMMENT '会话消息顺序查询',
  KEY `idx_msg_message_to_created` (`to_id`, `created_at`) COMMENT '接收对象时间范围查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息域权威消息';

CREATE TABLE `msg_read_status`
(
  `id`         bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `msg_id`     varchar(64) NOT NULL COMMENT '消息全局标识',
  `user_id`    bigint NOT NULL COMMENT '已读用户 ID',
  `read_at`    datetime(3) NOT NULL COMMENT '已读时间',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_read_status_msg_user` (`msg_id`, `user_id`) COMMENT '消息已读状态幂等约束',
  KEY `idx_msg_read_status_user` (`user_id`, `read_at`) COMMENT '用户已读记录时间查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息已读状态';

CREATE TABLE `msg_outbox`
(
  `id`             bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_id`       varchar(64) NOT NULL COMMENT '事件幂等键',
  `aggregate_type` varchar(32) NOT NULL COMMENT '聚合类型',
  `aggregate_id`   varchar(64) NOT NULL COMMENT '聚合标识',
  `topic`          varchar(128) NOT NULL COMMENT 'RocketMQ 消息主题',
  `sharding_key`   varchar(128) NOT NULL COMMENT '有序投递分片键',
  `payload_json`   json NOT NULL COMMENT '事件载荷',
  `published`      tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已发布至 RocketMQ',
  `published_at`   datetime(3) DEFAULT NULL COMMENT '成功发布时间',
  `created_by`     bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`     bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_outbox_event_id` (`event_id`) COMMENT '事件幂等约束',
  KEY `idx_msg_outbox_pending` (`published`, `id`) COMMENT '待发布事件按主键顺序扫描'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息域待发布事件';

CREATE TABLE `msg_conversation_sequence`
(
  `conversation_id` varchar(128) NOT NULL COMMENT '会话标识，同步序列归属会话',
  `last_seq`        bigint NOT NULL DEFAULT 0 COMMENT '该会话已分配的最大消息序号',
  `created_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  -- 主键 PRIMARY：保证每个会话仅维护一条序列，供行锁串行分配消息序号。
  PRIMARY KEY (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息会话序列';

CREATE TABLE `msg_user_sync_sequence`
(
  `user_id`       bigint NOT NULL COMMENT '用户 ID，同步序列归属用户',
  `last_sync_seq` bigint NOT NULL DEFAULT 0 COMMENT '该用户已分配的最大同步序号',
  `created_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  -- 主键 PRIMARY：保证每个用户仅维护一条同步序列，供行锁串行分配序号。
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户消息同步序列';

CREATE TABLE `msg_user_sync_index`
(
  `user_id`         bigint NOT NULL COMMENT '接收或发送用户 ID，同步索引归属用户',
  `sync_seq`        bigint NOT NULL COMMENT '用户维度单调递增同步序号',
  `msg_id`          varchar(64) NOT NULL COMMENT '消息全局标识，关联消息域权威消息',
  `conversation_id` varchar(128) NOT NULL COMMENT '会话标识，用于客户端按会话归并同步结果',
  `created_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  -- 主键 PRIMARY：支持按用户和同步序号稳定分页拉取消息。
  PRIMARY KEY (`user_id`, `sync_seq`),
  UNIQUE KEY `uk_msg_user_sync_index_user_msg` (`user_id`, `msg_id`) COMMENT '用户消息同步索引幂等约束，避免同一消息重复写入用户索引',
  KEY `idx_msg_user_sync_index_msg` (`msg_id`) COMMENT '按消息标识删除全部用户同步索引的查询路径'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户消息同步权威索引';

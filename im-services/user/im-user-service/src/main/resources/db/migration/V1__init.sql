-- 用户域数据库基线：仅适用于尚未执行此基线的新建数据库。

CREATE TABLE `user`
(
  `id`         bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`   varchar(64) NOT NULL COMMENT '登录用户名，全局唯一',
  `nickname`   varchar(128) NOT NULL DEFAULT '' COMMENT '显示昵称',
  `avatar`     varchar(512) NOT NULL DEFAULT '' COMMENT '头像 URL',
  `password`   varchar(256) NOT NULL COMMENT '密码哈希',
  `email`      varchar(256) DEFAULT NULL COMMENT '邮箱',
  `phone`      varchar(32) DEFAULT NULL COMMENT '手机号',
  `signature`  varchar(512) NOT NULL DEFAULT '' COMMENT '个性签名',
  `status`     enum('active','disabled') NOT NULL DEFAULT 'active' COMMENT '账号状态',
  `status_version` bigint unsigned NOT NULL DEFAULT '1' COMMENT '用户状态版本',
  `role`       enum('user','admin') NOT NULL DEFAULT 'user' COMMENT '用户角色',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`),
  KEY `idx_user_phone` (`phone`),
  KEY `idx_user_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户';

CREATE TABLE `user_device_token`
(
  `id`            bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`       bigint unsigned NOT NULL COMMENT '所属用户 ID',
  `token`         varchar(512) NOT NULL COMMENT '推送令牌',
  `push_provider` enum('apns','fcm','jpush') NOT NULL DEFAULT 'fcm' COMMENT '推送渠道',
  `platform`      enum('android','ios','web','windows','macos') NOT NULL COMMENT '客户端平台',
  `device_id`     varchar(128) DEFAULT NULL COMMENT '设备唯一标识',
  `enabled`       tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `created_by`    bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`    bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_device_token_provider_token` (`push_provider`, `token`),
  KEY `idx_user_device_token_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户设备推送令牌';

CREATE TABLE `user_friend_request`
(
  `id`           bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `from_user_id` bigint unsigned NOT NULL COMMENT '发起方用户 ID',
  `to_user_id`   bigint unsigned NOT NULL COMMENT '接收方用户 ID',
  `message`      varchar(256) NOT NULL DEFAULT '' COMMENT '好友申请附言',
  `status`       enum('pending','accepted','rejected') NOT NULL DEFAULT 'pending' COMMENT '申请状态',
  `created_by`   bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`   datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`   bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`   datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_friend_request_to_status` (`to_user_id`, `status`),
  KEY `idx_user_friend_request_from` (`from_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户好友申请';

CREATE TABLE `user_friend`
(
  `id`         bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`    bigint unsigned NOT NULL COMMENT '关系持有者用户 ID',
  `friend_id`  bigint unsigned NOT NULL COMMENT '好友用户 ID',
  `remark`     varchar(128) NOT NULL DEFAULT '' COMMENT '好友备注',
  `group_name` varchar(64) NOT NULL DEFAULT '默认分组' COMMENT '好友分组',
  `status`     enum('normal','blocked') NOT NULL DEFAULT 'normal' COMMENT '关系状态',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_friend_user_friend` (`user_id`, `friend_id`),
  KEY `idx_user_friend_friend` (`friend_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户好友关系';

CREATE TABLE `user_point_account`
(
  `id`         bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`    bigint unsigned NOT NULL COMMENT '所属用户 ID',
  `balance`    int unsigned NOT NULL DEFAULT '0' COMMENT '当前可用积分',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_point_account_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户积分账户';

CREATE TABLE `user_point_ledger`
(
  `id`            bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`       bigint unsigned NOT NULL COMMENT '所属用户 ID',
  `entry_type`    enum('credit','debit') NOT NULL COMMENT '积分变动类型',
  `amount`        int unsigned NOT NULL COMMENT '变动数量',
  `balance_after` int unsigned NOT NULL COMMENT '变动后余额',
  `reason`        varchar(512) NOT NULL COMMENT '变动原因',
  `business_type` varchar(64) DEFAULT NULL COMMENT '业务类型',
  `business_order_no` varchar(64) DEFAULT NULL COMMENT '业务单号',
  `command_id` varchar(64) DEFAULT NULL COMMENT '积分操作幂等键',
  `operator_user_id` bigint unsigned DEFAULT NULL COMMENT '操作人 ID',
  `created_by`    bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`    bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_point_ledger_user_created` (`user_id`, `created_at`),
  KEY `idx_user_point_ledger_business` (`business_type`, `business_order_no`),
  UNIQUE KEY `uk_user_point_ledger_command_id` (`command_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户积分流水';

CREATE TABLE `user_sticker`
(
  `id`         bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`    bigint unsigned NOT NULL COMMENT '所属用户 ID',
  `url`        varchar(500) NOT NULL COMMENT '表情图片 URL',
  `thumbnail`  varchar(500) DEFAULT NULL COMMENT '缩略图 URL',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序权重',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_sticker_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表情';

CREATE TABLE `user_sticker_quota`
(
  `user_id`       bigint unsigned NOT NULL COMMENT '所属用户 ID',
  `sticker_count` int unsigned NOT NULL DEFAULT '0' COMMENT '已占用贴纸名额',
  `created_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户贴纸名额';

CREATE TABLE `user_outbox`
(
  `id`             bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_id`       varchar(64) NOT NULL COMMENT '事件幂等键',
  `aggregate_type` varchar(64) NOT NULL COMMENT '聚合类型',
  `aggregate_id`   varchar(64) NOT NULL COMMENT '聚合标识',
  `topic`          varchar(128) NOT NULL COMMENT '消息主题',
  `sharding_key`   varchar(128) NOT NULL COMMENT '顺序分片键',
  `payload_json`   json NOT NULL COMMENT '事件载荷',
  `published`      tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已发布',
  `published_at`   datetime(3) DEFAULT NULL COMMENT '发布时间',
  `created_by`     bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`     bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_outbox_event` (`event_id`),
  KEY `idx_user_outbox_pending` (`published`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户域待发布事件';

CREATE TABLE `user_admin_status_operation`
(
  `id`               bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `idempotency_key`  varchar(128) NOT NULL COMMENT '幂等键',
  `user_id`          bigint unsigned NOT NULL COMMENT '目标用户 ID',
  `expected_version` bigint unsigned NOT NULL COMMENT '请求状态版本',
  `previous_status`  varchar(16) NOT NULL COMMENT '变更前状态',
  `current_status`   varchar(16) NOT NULL COMMENT '变更后状态',
  `status_version`   bigint unsigned NOT NULL COMMENT '变更后状态版本',
  `operator_id`      bigint unsigned NOT NULL COMMENT '操作人 ID',
  `reason`           varchar(512) NOT NULL COMMENT '操作理由',
  `correlation_id`   varchar(128) NOT NULL COMMENT '关联 ID',
  `request_version`  int unsigned NOT NULL COMMENT '请求契约版本',
  `created_at`       datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_admin_status_operation_idempotency` (`idempotency_key`),
  KEY `idx_user_admin_status_operation_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户管理状态操作审计';

INSERT INTO `user`
  (`id`, `username`, `nickname`, `avatar`, `password`, `email`, `phone`, `signature`, `status`, `role`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  (1, 'admin', '管理员', '', '$2a$10$TMyvg5zRRWXkwk9KYlMgZOq9VIbAmDnjk4kpFSZgWDun7oFmseP9W', NULL, NULL, '', 'active', 'admin', 0, CURRENT_TIMESTAMP(3), 0, CURRENT_TIMESTAMP(3));

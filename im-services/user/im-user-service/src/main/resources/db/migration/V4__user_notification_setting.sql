-- 用户离线推送通知设置（私聊/群聊/频道三类分别开关，默认开启）

CREATE TABLE `user_notification_setting`
(
  `user_id`        bigint unsigned NOT NULL COMMENT '用户标识（与 user.id 一致）',
  `notify_private` tinyint(1) NOT NULL DEFAULT 1 COMMENT '私聊离线推送',
  `notify_group`   tinyint(1) NOT NULL DEFAULT 1 COMMENT '群聊离线推送',
  `notify_channel` tinyint(1) NOT NULL DEFAULT 1 COMMENT '频道离线推送',
  `created_at`     datetime(3) NOT NULL COMMENT '创建时间',
  `updated_at`     datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户离线推送通知设置权威表';

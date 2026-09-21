-- 用户隐私设置（群聊好友添加开关等）

CREATE TABLE `user_privacy_setting`
(
  `user_id`                  bigint unsigned NOT NULL COMMENT '用户标识（与 user.id 一致）',
  `allow_group_friend_request` tinyint NOT NULL DEFAULT 1 COMMENT '允许通过群聊添加我为好友',
  `created_at`               datetime(3) NOT NULL COMMENT '创建时间',
  `updated_at`               datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户隐私设置权威表';

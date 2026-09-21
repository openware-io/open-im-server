-- 群成员隐私保护：隐藏非好友成员的用户名和头像（默认开启保护）
ALTER TABLE `user_privacy_setting`
  ADD COLUMN `hide_group_member_info` tinyint NOT NULL DEFAULT 1 COMMENT '隐藏非好友群成员用户名头像' AFTER `allow_group_friend_request`;

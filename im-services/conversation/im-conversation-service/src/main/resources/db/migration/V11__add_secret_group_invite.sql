-- 私密群聊「邀请链接」：群主生成带有效期令牌，成员凭令牌加入。
ALTER TABLE `conversation_secret_group_chat`
  ADD COLUMN `invite_token` varchar(64) DEFAULT NULL COMMENT '邀请令牌（群主生成）' AFTER `pinned_at`,
  ADD COLUMN `invite_expires_at` datetime(3) DEFAULT NULL COMMENT '邀请过期时间' AFTER `invite_token`;

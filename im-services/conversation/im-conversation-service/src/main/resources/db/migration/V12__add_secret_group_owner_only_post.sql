-- 私密群聊「精细权限」：仅群主可发言（owner_only_post）。
ALTER TABLE `conversation_secret_group_chat`
  ADD COLUMN `owner_only_post` tinyint(1) NOT NULL DEFAULT 0 COMMENT '仅群主可发言开关(0关/1开)' AFTER `invite_expires_at`;

-- 私密群聊「匿名发言」：群主可开启，开启后群内消息发送者身份对成员隐藏（仅群主可见）。
ALTER TABLE `conversation_secret_group_chat`
  ADD COLUMN `anonymous_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '匿名发言开关(0关/1开)' AFTER `destroy_policy`;

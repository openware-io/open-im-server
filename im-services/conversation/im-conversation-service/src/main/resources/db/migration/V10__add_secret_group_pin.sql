-- 私密群聊「置顶消息」：群主可置顶一条消息（公告性质），成员在群设置/顶部可见。
ALTER TABLE `conversation_secret_group_chat`
  ADD COLUMN `pinned_msg_id` varchar(64) DEFAULT NULL COMMENT '置顶消息 msgId' AFTER `anonymous_enabled`,
  ADD COLUMN `pinned_at` datetime(3) DEFAULT NULL COMMENT '置顶时间' AFTER `pinned_msg_id`;

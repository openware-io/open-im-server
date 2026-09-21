-- 私密群聊「群名称 + 群公告」：与普通群聊管理对齐，群主可改名、编辑公告。
ALTER TABLE `conversation_secret_group_chat`
  ADD COLUMN `name` varchar(128) DEFAULT NULL COMMENT '群名称' AFTER `status`,
  ADD COLUMN `announcement` varchar(2000) DEFAULT NULL COMMENT '群公告' AFTER `name`;

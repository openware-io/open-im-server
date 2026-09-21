-- 私密群聊「Telegram 群组管理能力」功能开关种子
INSERT IGNORE INTO `adm_system_config` (`config_key`, `config_value`, `config_group`, `description`, `created_at`, `updated_at`) VALUES
('feature.groupDeleteEveryoneEnabled', 'true', 'feature', '私密群聊删除所有人/撤回开关', NOW(3), NOW(3)),
('feature.groupEditMessageEnabled', 'true', 'feature', '私密群聊编辑消息开关', NOW(3), NOW(3)),
('feature.groupAnonymityEnabled', 'false', 'feature', '私密群聊匿名发言开关', NOW(3), NOW(3)),
('feature.groupInviteLinkEnabled', 'true', 'feature', '私密群聊邀请链接开关', NOW(3), NOW(3)),
('feature.groupPinnedEnabled', 'true', 'feature', '私密群聊置顶/公告开关', NOW(3), NOW(3));

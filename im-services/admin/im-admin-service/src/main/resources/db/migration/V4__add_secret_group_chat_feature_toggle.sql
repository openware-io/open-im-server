-- 补「私密群聊」功能开关（此前 /config/client 已下发、客户端已消费，但 adm_system_config 未落库、后台无开关）
INSERT IGNORE INTO `adm_system_config` (`config_key`, `config_value`, `config_group`, `description`, `created_at`, `updated_at`) VALUES
('feature.secretGroupChatEnabled', 'true', 'feature', '私密群聊开关（对应 Telegram 群组业务 · 逐成员加密）', NOW(3), NOW(3));

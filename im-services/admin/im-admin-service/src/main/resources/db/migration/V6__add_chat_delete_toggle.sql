-- 聊天删除功能总开关（单一粗粒度开关，控制各类聊天的删除/撤回/清空）
INSERT IGNORE INTO `adm_system_config` (`config_key`, `config_value`, `config_group`, `description`, `created_at`, `updated_at`) VALUES
('feature.chatDeleteEnabled', 'true', 'feature', '聊天删除/撤回/清空总开关', NOW(3), NOW(3));

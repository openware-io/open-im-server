-- 初始化系统配置默认值（INSERT IGNORE：已有管理员自定义值不被覆盖）

-- ============ 基础配置（app） ============
INSERT IGNORE INTO `adm_system_config` (`config_key`, `config_value`, `config_group`, `description`, `created_at`, `updated_at`) VALUES
('app.name', 'WV Chat', 'app', '应用显示名称', NOW(3), NOW(3)),
('app.announcement', '', 'app', '系统公告（留空不显示）', NOW(3), NOW(3));

-- ============ 功能开关（feature） ============
INSERT IGNORE INTO `adm_system_config` (`config_key`, `config_value`, `config_group`, `description`, `created_at`, `updated_at`) VALUES
('feature.privateChatEnabled', 'true', 'feature', '云端单聊开关', NOW(3), NOW(3)),
('feature.groupChatEnabled', 'true', 'feature', '云端群组开关', NOW(3), NOW(3)),
('feature.recallEnabled', 'true', 'feature', '消息撤回开关', NOW(3), NOW(3)),
('feature.readReceiptEnabled', 'true', 'feature', '已读回执开关', NOW(3), NOW(3)),
('feature.voiceCallEnabled', 'true', 'feature', '语音通话开关', NOW(3), NOW(3)),
('feature.videoCallEnabled', 'true', 'feature', '视频通话开关', NOW(3), NOW(3)),
('feature.channelEnabled', 'true', 'feature', '频道（单向广播）开关', NOW(3), NOW(3)),
('feature.secretChatEnabled', 'true', 'feature', '私密聊天（E2EE）开关', NOW(3), NOW(3)),
('feature.imageAuditEnabled', 'false', 'feature', '图片内容审核开关', NOW(3), NOW(3)),
('feature.voiceAuditEnabled', 'false', 'feature', '语音内容审核开关', NOW(3), NOW(3));

-- ============ 通话配置（rtc） ============
INSERT IGNORE INTO `adm_system_config` (`config_key`, `config_value`, `config_group`, `description`, `created_at`, `updated_at`) VALUES
('rtc.videoQuality', '720p', 'rtc', '视频清晰度（360p/480p/720p/1080p）', NOW(3), NOW(3)),
('rtc.videoBitrate', '1500', 'rtc', '视频码率（kbps）', NOW(3), NOW(3)),
('rtc.audioBitrate', '64', 'rtc', '音频码率（kbps）', NOW(3), NOW(3)),
('rtc.maxCallDuration', '120', 'rtc', '最大通话时长（分钟，0 不限制）', NOW(3), NOW(3));

-- ============ 上传配置（upload） ============
INSERT IGNORE INTO `adm_system_config` (`config_key`, `config_value`, `config_group`, `description`, `created_at`, `updated_at`) VALUES
('upload.maxImageSize', '10', 'upload', '图片大小限制（MB）', NOW(3), NOW(3)),
('upload.maxFileSize', '50', 'upload', '文件大小限制（MB）', NOW(3), NOW(3)),
('upload.maxVideoSize', '100', 'upload', '视频大小限制（MB）', NOW(3), NOW(3));

-- ============ 推送配置（push） ============
INSERT IGNORE INTO `adm_system_config` (`config_key`, `config_value`, `config_group`, `description`, `created_at`, `updated_at`) VALUES
('push.enabled', 'true', 'push', '推送总开关', NOW(3), NOW(3)),
('push.apnsEnabled', 'true', 'push', 'APNs（iOS）推送开关', NOW(3), NOW(3)),
('push.fcmEnabled', 'false', 'push', 'FCM（Firebase）推送开关', NOW(3), NOW(3)),
('push.jpushEnabled', 'true', 'push', '极光 JPush 推送开关', NOW(3), NOW(3));

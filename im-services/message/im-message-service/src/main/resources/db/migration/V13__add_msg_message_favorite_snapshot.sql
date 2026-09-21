-- 收藏内容快照：为「收藏内容永远可读」补齐快照列（增量、可空，不回填历史数据）。
--
-- 版本号说明：本文件原为 V10，与更早提交的 V10__add_secret_message_unread_projection.sql 版本号冲突
-- （Flyway 会直接报 "Found more than one migration with version 10" 导致服务启动失败），故顺延为 V13。
-- 两文件同时存在时 Flyway 从未成功执行过，因此不存在任何环境把本迁移记录成 V10 的历史，改名不影响迁移历史。
--
-- 背景：V7 的「引用消息方案」只存 msgId + 会话定位，原消息被撤回/删除（权威消息硬删除）后
-- 列表只能渲染占位——与产品决策「收藏和微信一样，原消息没了也要能看收藏内容」冲突。
-- 因此收藏时同时写入一份内容快照；读取时优先用实时消息（保证能看到编辑后的最新内容），
-- 实时消息不存在或已无权访问时回落到快照。历史行快照为空 → 行为与改动前完全一致。
--
-- 注意：快照只服务「收藏」这一份用户数据视图，权威消息仍在 msg_message，消息本体不在收藏删除时清理。

ALTER TABLE `msg_message_favorite`
  ADD COLUMN `msg_type_snapshot` varchar(32) NULL COMMENT '收藏时的消息类型快照（原消息删除后仍可渲染）' AFTER `chat_type`,
  ADD COLUMN `content_snapshot` text NULL COMMENT '收藏时的消息内容快照' AFTER `msg_type_snapshot`,
  ADD COLUMN `from_user_id_snapshot` bigint NULL COMMENT '收藏时的发送者用户 ID 快照' AFTER `content_snapshot`,
  ADD COLUMN `sender_username_snapshot` varchar(128) NULL COMMENT '收藏时的发送者用户名快照' AFTER `from_user_id_snapshot`,
  ADD COLUMN `sent_at_snapshot` datetime(3) NULL COMMENT '收藏时原消息的发送时间快照' AFTER `sender_username_snapshot`;

-- 数据库规范修复（V3/V4 已部署，此脚本修正表名前缀/审计字段/主键 unsigned）
-- 表名统一 conversation_ 前缀（会话域），补审计字段，主键改 bigint unsigned

RENAME TABLE `channel` TO `conversation_channel`;
RENAME TABLE `channel_subscription` TO `conversation_channel_subscription`;
RENAME TABLE `secret_chat` TO `conversation_secret_chat`;

ALTER TABLE `conversation_channel`
  MODIFY `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  MODIFY `owner_id` bigint unsigned NOT NULL,
  ADD COLUMN `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  ADD COLUMN `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人';

ALTER TABLE `conversation_channel_subscription`
  MODIFY `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  MODIFY `channel_id` bigint unsigned NOT NULL,
  MODIFY `user_id` bigint unsigned NOT NULL,
  ADD COLUMN `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  ADD COLUMN `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  ADD COLUMN `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  ADD COLUMN `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间';

ALTER TABLE `conversation_secret_chat`
  MODIFY `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  MODIFY `user_a` bigint unsigned NOT NULL,
  MODIFY `user_b` bigint unsigned NOT NULL;

-- 索引名对齐 uk_/idx_<table>_<semantic>
ALTER TABLE `conversation_channel` RENAME INDEX `idx_channel_owner_id` TO `idx_conversation_channel_owner_id`;
ALTER TABLE `conversation_channel_subscription` RENAME INDEX `uk_channel_subscription_channel_user` TO `uk_conversation_channel_subscription_channel_user`;
ALTER TABLE `conversation_channel_subscription` RENAME INDEX `idx_channel_subscription_user_id` TO `idx_conversation_channel_subscription_user_id`;
ALTER TABLE `conversation_secret_chat` RENAME INDEX `uk_secret_chat_users` TO `uk_conversation_secret_chat_users`;
ALTER TABLE `conversation_secret_chat` RENAME INDEX `idx_secret_chat_user_b` TO `idx_conversation_secret_chat_user_b`;

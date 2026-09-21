-- 消息编辑能力：发送后 2 分钟内允许发送者编辑正文（含已被对方读的消息，类似 Telegram）。
-- edited 标记消息是否被编辑过；edited_at 记录最近一次编辑时间（客户端据此渲染「已编辑」标记）。
-- 撤回/删除仍走既有 msg_message 硬删除 + tombstone 语义，本迁移只新增编辑相关状态列，只增不改。
ALTER TABLE `msg_message`
  ADD COLUMN `edited` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否已被编辑（发送后 2 分钟内可编辑）' AFTER `status`,
  ADD COLUMN `edited_at` datetime(3) NULL COMMENT '最近编辑时间' AFTER `edited`;

-- 资料同步事件消费幂等：event_id 唯一键兜底去重（bind 拉取路径 event_id 为空，多个 NULL 不冲突）
ALTER TABLE `idt_profile_sync_record`
  ADD COLUMN `event_id` varchar(64) NULL COMMENT '事件ID(消费幂等键，bind 拉取为空)' AFTER `id`,
  ADD UNIQUE KEY `uk_idt_profile_sync_event` (`event_id`);

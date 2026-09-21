-- 消息已读状态按用户和消息标识批量同步查询索引：支持 /messages/sync 回填当前用户已读状态。
ALTER TABLE `msg_read_status`
  ADD KEY `idx_msg_read_status_user_msg` (`user_id`, `msg_id`) COMMENT '用户消息已读状态同步查询';

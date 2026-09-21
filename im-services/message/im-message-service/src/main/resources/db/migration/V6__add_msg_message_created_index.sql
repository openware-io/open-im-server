-- 管理端消息列表按创建时间倒序分页（ORDER BY created_at DESC）此前无可用索引，
-- 导致全表扫描 + filesort + COUNT 全扫，大表下响应极慢。
-- InnoDB 二级索引会隐式追加主键 id，故 (created_at) 实际等价于 (created_at, id)，
-- 可同时服务排序与确定性 tie-break。
ALTER TABLE `msg_message`
  ADD KEY `idx_msg_message_created` (`created_at`) COMMENT '管理端按创建时间倒序分页';

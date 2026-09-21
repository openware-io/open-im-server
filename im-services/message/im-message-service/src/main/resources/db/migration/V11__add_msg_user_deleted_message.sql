-- 用户「删除仅我」消息墓碑：仅对该用户隐藏，其余成员不受影响；跨重装保留。
--
-- 背景：此前「删除仅我」是**纯本地**操作（只把 msgId 写进客户端隐藏列表），
-- 卸载重装后本地列表丢失，重新同步会把消息“复活”。
-- 本表把「该用户删过哪些消息」持久化到服务端，同步/未读/历史查询统一按它过滤。
--
-- 注意：与 msg_message 的硬删除语义不同——本表只影响**单个用户**的可见性，
-- 消息本体与其它成员的可见性保持不变；也不做外键约束，避免消息硬删后阻塞。

CREATE TABLE `msg_user_deleted_message`
(
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`         bigint NOT NULL COMMENT '执行「删除仅我」的用户 ID',
  `msg_id`          varchar(64) NOT NULL COMMENT '被删除的消息全局标识（引用 msg_message.msg_id，不做外键约束）',
  `conversation_id` varchar(128) NOT NULL COMMENT '消息所属会话 ID（便于按会话清理与排查）',
  `chat_type`       varchar(32) NOT NULL COMMENT '会话类型，例如 PRIVATE / GROUP',
  `created_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '删除时间（UTC）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_user_deleted_user_msg` (`user_id`, `msg_id`) COMMENT '同一用户重复删除幂等',
  KEY `idx_msg_user_deleted_user_conversation` (`user_id`, `conversation_id`) COMMENT '按用户+会话过滤',
  KEY `idx_msg_user_deleted_created` (`created_at`) COMMENT '按时间清理'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='用户「删除仅我」的消息墓碑（仅对该用户隐藏，跨重装保留）';

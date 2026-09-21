-- 会话清空标记（per-user）：记录某用户在某会话上的「清空到哪个时刻」。
--
-- 背景：清空会话（删除服务器）此前只依赖 WS 实时通知让对端清理本地记录，
-- 而 `WsBroadcastService.sendToUser` 走 Redis 房间广播且**无论是否有订阅者都返回成功**，
-- 因此**对方离线时通知会永久丢失**；而服务端消息已被删除，增量同步（按 syncSeq）
-- 又不会告知客户端删除，导致对方本地旧记录一直残留（线上现象）。
--
-- 本表把「清空」持久化下来：客户端每次同步都能拿到自己名下所有会话的清空时刻，
-- 据此删除本地早于该时刻的消息——离线、换端、重装都能自愈。
--
-- 注意：清空是**双方语义**（对齐微信「删除服务器」），因此清理发起方与对方都会各写一条，
-- 各自只影响自己的本地记录，不改变消息本体。

CREATE TABLE `msg_user_conversation_clear`
(
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`         bigint NOT NULL COMMENT '该标记归属的用户 ID',
  `conversation_id` varchar(128) NOT NULL COMMENT '被清空的会话 ID',
  `chat_type`       varchar(32) NOT NULL COMMENT '会话类型：private / group',
  `cleared_at`      datetime(3) NOT NULL COMMENT '清空时刻（UTC）；客户端删除早于该时刻的本地消息',
  `cleared_by`      bigint NOT NULL COMMENT '发起清空的用户 ID（用于排查与展示）',
  `created_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_user_conversation_clear` (`user_id`, `conversation_id`) COMMENT '同一用户同一会话只保留最新清空时刻',
  KEY `idx_msg_user_conversation_clear_user_created` (`user_id`, `created_at`) COMMENT '按用户增量拉取'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='会话清空标记（per-user，供离线端在同步时自愈本地记录）';

-- 私密消息销毁痕迹表：撤回/删除/定时销毁后仅保留 msgId + 销毁时刻 + 原因，
-- 供离线端增量同步移除/墓碑（无密文、无内容、无发送者），密文本体在 msg_secret_message 硬删除。

CREATE TABLE `msg_secret_message_destroyed`
(
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '销毁记录标识',
  `secret_chat_id`  bigint unsigned NOT NULL COMMENT '私密会话标识',
  `msg_id`          varchar(64) NOT NULL COMMENT '已销毁消息标识',
  `destroy_at`      datetime(3) NOT NULL COMMENT '销毁时刻（服务端协调，增量同步游标）',
  `reason`          varchar(16) NOT NULL COMMENT '销毁原因：destroyed/recalled/deleted',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_secret_destroyed_chat_msg` (`secret_chat_id`, `msg_id`),
  KEY `idx_secret_destroyed_chat_time` (`secret_chat_id`, `destroy_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私密消息销毁痕迹表（无密文/内容）';

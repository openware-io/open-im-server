-- 消息域（im-message-service）数据库基线。
--
-- 基线重建（批次 2）：本文件由原 V1..V13 十三个迁移脚本合并而成，单独即可产出最终结构。
-- 合并动作：
--   1. 后版本 ALTER TABLE 追加的列（`edited` / `edited_at`、收藏快照五列）与新增索引
--      （`idx_msg_read_status_user_msg`、`idx_msg_message_created`）直接折叠进建表语句；
--   2. 10 张表补齐 4 个审计列（`created_by` / `updated_by` / `created_at` / `updated_at`，DC-11）；
--   3. 4 张表把业务键/复合键主键改为 `id bigint unsigned NOT NULL AUTO_INCREMENT`，
--      旧主键降级为唯一索引：
--        `msg_conversation_sequence`       : (`conversation_id`)
--        `msg_user_sync_sequence`          : (`user_id`)
--        `msg_user_sync_index`             : (`user_id`, `sync_seq`)
--        `msg_secret_unread`               : (`chat_type`, `user_id`, `msg_id`)
--      旧键一律以 UNIQUE KEY 保留，语义（每会话/每用户一行、幂等、分页游标唯一）不变；
--   4. 索引名统一为 `idx_<完整表名>_<语义>` / `uk_<完整表名>_<语义>`；
--   5. `msg_friend_accept_message` 的排序规则由 `utf8mb4_unicode_ci` 归并为
--      `utf8mb4_0900_ai_ci`，全库一致；
--   6. 被删除的原脚本中没有播种（INSERT）与数据订正（UPDATE）语句，也没有跨域数据语句，
--      故本基线不含任何数据语句。
--
-- 语义保持：原有列的类型、可空性、默认值、业务约束与中文列注释一律保留（例如
-- `msg_secret_message`.`created_by` 的注释仍为「创建人」、`msg_secret_unread`.`created_at`
-- 的注释仍为「投影创建时间」）。对既有列只有两类刻意改动：
--   a. 时间列原先「无默认值」的补上 `DEFAULT CURRENT_TIMESTAMP(3)`（见下）；
--   b. `msg_friend_accept_message`.`id`、`msg_secret_group_message`.`id`、
--      `msg_secret_group_message_destroyed`.`id` 由 `bigint` 改为 `bigint unsigned`（门禁硬要求）。
--
-- 审计列默认值说明：`created_by` / `updated_by` 默认 0；`created_at` / `updated_at`
-- 默认 `CURRENT_TIMESTAMP(3)`（`updated_at` 带 `ON UPDATE`）。这是必要的兜底——`msg_*`
-- 多张表由 MyBatis-Plus `BaseMapper.insert` 写入，PO 未覆盖的审计列在 INSERT 列表中被省略，
-- 无默认值时 MySQL 8 严格模式会以 1364 拒插。显式写入审计列的 @Insert 亦已同步补列：
-- `msg_conversation_sequence`、`msg_user_sync_sequence`、`msg_user_conversation_clear`。

-- 消息域权威消息。
-- 主键 `id` 为代理键；`msg_id` 为全局幂等键，`(from_user_id, client_msg_id)` 为客户端重试幂等键，
-- `(conversation_id, seq)` 为会话内顺序键，三者均为唯一约束。
CREATE TABLE `msg_message`
(
  `id`               bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `msg_id`           varchar(64) NOT NULL COMMENT '消息全局标识，也是消息写入幂等键',
  `conversation_id`  varchar(128) NOT NULL COMMENT '会话标识，用于消息顺序与查询',
  `seq`              bigint NOT NULL COMMENT '会话内单调递增序号',
  `from_user_id`     bigint NOT NULL COMMENT '发送用户 ID',
  `sender_username`  varchar(128) NOT NULL DEFAULT '' COMMENT '发送用户名快照',
  `to_id`            varchar(64) NOT NULL COMMENT '接收对象标识',
  `chat_type`        varchar(32) NOT NULL COMMENT '聊天类型，例如 private 或 group',
  `msg_type`         varchar(32) NOT NULL COMMENT '消息类型，例如 text 或 image',
  `content`          text NOT NULL COMMENT '消息正文',
  `client_msg_id`    varchar(128) DEFAULT NULL COMMENT '客户端消息标识，用于客户端重试去重',
  `reply_msg_id`     varchar(64) DEFAULT NULL COMMENT '被回复消息标识',
  `at_users`         json DEFAULT NULL COMMENT '@用户标识列表',
  `media_object_ids` json DEFAULT NULL COMMENT '受管媒体对象标识列表',
  `status`           varchar(32) NOT NULL COMMENT '消息状态',
  `edited`           tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否已被编辑（发送后 2 分钟内可编辑）',
  `edited_at`        datetime(3) DEFAULT NULL COMMENT '最近编辑时间',
  `created_by`       bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`       datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`       bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`       datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_message_msg_id` (`msg_id`) COMMENT '消息全局幂等约束',
  UNIQUE KEY `uk_msg_message_sender_client` (`from_user_id`, `client_msg_id`) COMMENT '客户端重试幂等约束',
  UNIQUE KEY `uk_msg_message_conversation_seq` (`conversation_id`, `seq`) COMMENT '会话内消息顺序唯一约束',
  KEY `idx_msg_message_conversation_seq` (`conversation_id`, `seq`) COMMENT '会话消息顺序查询',
  KEY `idx_msg_message_to_created` (`to_id`, `created_at`) COMMENT '接收对象时间范围查询',
  KEY `idx_msg_message_created` (`created_at`) COMMENT '管理端按创建时间倒序分页（InnoDB 隐式追加主键 id，等价于 (created_at, id)）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息域权威消息';

-- 消息已读状态。
CREATE TABLE `msg_read_status`
(
  `id`         bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `msg_id`     varchar(64) NOT NULL COMMENT '消息全局标识',
  `user_id`    bigint NOT NULL COMMENT '已读用户 ID',
  `read_at`    datetime(3) NOT NULL COMMENT '已读时间',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_read_status_msg_user` (`msg_id`, `user_id`) COMMENT '消息已读状态幂等约束',
  KEY `idx_msg_read_status_user` (`user_id`, `read_at`) COMMENT '用户已读记录时间查询',
  KEY `idx_msg_read_status_user_msg` (`user_id`, `msg_id`) COMMENT '用户消息已读状态同步查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息已读状态';

-- 消息域待发布事件（事务性发件箱）。
CREATE TABLE `msg_outbox`
(
  `id`             bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_id`       varchar(64) NOT NULL COMMENT '事件幂等键',
  `aggregate_type` varchar(32) NOT NULL COMMENT '聚合类型',
  `aggregate_id`   varchar(64) NOT NULL COMMENT '聚合标识',
  `topic`          varchar(128) NOT NULL COMMENT 'RocketMQ 消息主题',
  `sharding_key`   varchar(128) NOT NULL COMMENT '有序投递分片键',
  `payload_json`   json NOT NULL COMMENT '事件载荷',
  `published`      tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已发布至 RocketMQ',
  `published_at`   datetime(3) DEFAULT NULL COMMENT '成功发布时间',
  `created_by`     bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`     bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_outbox_event_id` (`event_id`) COMMENT '事件幂等约束',
  KEY `idx_msg_outbox_pending` (`published`, `id`) COMMENT '待发布事件按主键顺序扫描'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息域待发布事件';

-- 消息会话序列。
-- 原主键为业务键 `conversation_id`，本次改为代理键 `id`，业务键降级为唯一索引
-- `uk_msg_conversation_sequence_conversation_id`：每会话仍只允许一行，供行锁串行分配消息序号。
CREATE TABLE `msg_conversation_sequence`
(
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `conversation_id` varchar(128) NOT NULL COMMENT '会话标识，同步序列归属会话',
  `last_seq`        bigint NOT NULL DEFAULT 0 COMMENT '该会话已分配的最大消息序号',
  `created_by`      bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`      bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_conversation_sequence_conversation_id` (`conversation_id`) COMMENT '每个会话仅维护一条序列（原业务键主键，行锁串行分配消息序号）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息会话序列';

-- 用户消息同步序列。
-- 原主键为业务键 `user_id`，本次改为代理键 `id`，业务键降级为唯一索引
-- `uk_msg_user_sync_sequence_user_id`：每用户仍只允许一行，供行锁串行分配同步序号。
CREATE TABLE `msg_user_sync_sequence`
(
  `id`            bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`       bigint NOT NULL COMMENT '用户 ID，同步序列归属用户',
  `last_sync_seq` bigint NOT NULL DEFAULT 0 COMMENT '该用户已分配的最大同步序号',
  `created_by`    bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`    bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_user_sync_sequence_user_id` (`user_id`) COMMENT '每个用户仅维护一条同步序列（原业务键主键，行锁串行分配序号）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户消息同步序列';

-- 用户消息同步权威索引。
-- 原主键为复合键 `(user_id, sync_seq)`，本次改为代理键 `id`，复合键降级为唯一索引
-- `uk_msg_user_sync_index_user_seq`：仍保证按用户 + 同步序号稳定分页的唯一游标语义。
CREATE TABLE `msg_user_sync_index`
(
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`         bigint NOT NULL COMMENT '接收或发送用户 ID，同步索引归属用户',
  `sync_seq`        bigint NOT NULL COMMENT '用户维度单调递增同步序号',
  `msg_id`          varchar(64) NOT NULL COMMENT '消息全局标识，关联消息域权威消息',
  `conversation_id` varchar(128) NOT NULL COMMENT '会话标识，用于客户端按会话归并同步结果',
  `created_by`      bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`      bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_user_sync_index_user_seq` (`user_id`, `sync_seq`) COMMENT '用户同步序号游标唯一（原复合主键）',
  UNIQUE KEY `uk_msg_user_sync_index_user_msg` (`user_id`, `msg_id`) COMMENT '用户消息同步索引幂等约束，避免同一消息重复写入用户索引',
  KEY `idx_msg_user_sync_index_msg` (`msg_id`) COMMENT '按消息标识删除全部用户同步索引的查询路径'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户消息同步权威索引';

-- 私密聊天密文消息权威表（E2EE：服务端仅存储密文，不解密；定时销毁由 destroy_at 驱动）。
CREATE TABLE `msg_secret_message`
(
  `id`             bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '密文消息标识',
  `secret_chat_id` bigint unsigned NOT NULL COMMENT '私密会话标识',
  `msg_id`         varchar(64) NOT NULL COMMENT '业务消息标识',
  `from_user_id`   bigint unsigned NOT NULL COMMENT '发送者用户标识',
  `ciphertext`     text NOT NULL COMMENT '客户端加密密文（服务端不解密）',
  `seq`            bigint NOT NULL COMMENT '会话内递增序号',
  `status`         varchar(32) NOT NULL DEFAULT 'active' COMMENT '消息状态',
  `destroy_at`     datetime(3) DEFAULT NULL COMMENT '定时销毁时间（已读后计时）',
  `created_by`     bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人',
  `created_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`     bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人',
  `updated_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_secret_message_msg_id` (`msg_id`) COMMENT '消息幂等唯一约束',
  KEY `idx_msg_secret_message_chat_seq` (`secret_chat_id`, `seq`) COMMENT '按会话游标拉取',
  KEY `idx_msg_secret_message_destroy_at` (`destroy_at`) COMMENT '定时销毁扫描'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私密聊天密文消息权威表';

-- 私密消息销毁痕迹表：撤回/删除/定时销毁后仅保留 msgId + 销毁时刻 + 原因，
-- 供离线端增量同步移除/墓碑（无密文、无内容、无发送者），密文本体在 msg_secret_message 硬删除。
CREATE TABLE `msg_secret_message_destroyed`
(
  `id`             bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '销毁记录标识',
  `secret_chat_id` bigint unsigned NOT NULL COMMENT '私密会话标识',
  `msg_id`         varchar(64) NOT NULL COMMENT '已销毁消息标识',
  `destroy_at`     datetime(3) NOT NULL COMMENT '销毁时刻（服务端协调，增量同步游标）',
  `reason`         varchar(16) NOT NULL COMMENT '销毁原因：destroyed/recalled/deleted',
  `created_by`     bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`     bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_secret_message_destroyed_chat_msg` (`secret_chat_id`, `msg_id`) COMMENT '同一会话同一消息仅保留一条销毁痕迹',
  KEY `idx_msg_secret_message_destroyed_chat_time` (`secret_chat_id`, `destroy_at`) COMMENT '按会话增量拉取销毁痕迹'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私密消息销毁痕迹表（无密文/内容）';

-- 私密群聊消息权威表（逐成员密文）。
CREATE TABLE `msg_secret_group_message`
(
  `id`                bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '消息标识',
  `secret_group_id`   bigint NOT NULL COMMENT '私密群聊标识',
  `msg_id`            varchar(64) NOT NULL COMMENT '客户端消息标识（幂等）',
  `from_user_id`      bigint NOT NULL COMMENT '发送方用户标识',
  `recipient_user_id` bigint NOT NULL COMMENT '接收方用户标识（每成员一份密文）',
  `ciphertext`        text NOT NULL COMMENT '密文（服务端不解密）',
  `seq`               bigint NOT NULL COMMENT '群内单调递增序号（游标同步）',
  `status`            varchar(32) NOT NULL DEFAULT 'active' COMMENT '状态(active/destroyed/recalled)',
  `destroy_at`        datetime(3) DEFAULT NULL COMMENT '定时销毁时间',
  `created_by`        bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`        datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`        bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`        datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_secret_group_message_group_msg_recipient` (`secret_group_id`, `msg_id`, `recipient_user_id`) COMMENT '群内逐成员密文幂等约束',
  KEY `idx_msg_secret_group_message_group_recipient_seq` (`secret_group_id`, `recipient_user_id`, `seq`) COMMENT '按群与成员游标拉取'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私密群聊消息权威表（逐成员密文）';

-- 私密群聊消息销毁痕迹表（增量同步）。
CREATE TABLE `msg_secret_group_message_destroyed`
(
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '销毁痕迹标识',
  `secret_group_id` bigint NOT NULL COMMENT '私密群聊标识',
  `msg_id`          varchar(64) NOT NULL COMMENT '消息标识',
  `destroy_at`      datetime(3) NOT NULL COMMENT '销毁时刻',
  `reason`          varchar(32) NOT NULL COMMENT '销毁原因(destroyed/recalled/deleted)',
  `created_by`      bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`      bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_msg_secret_group_message_destroyed_group_time` (`secret_group_id`, `destroy_at`) COMMENT '按群增量拉取销毁痕迹'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私密群聊消息销毁痕迹表（增量同步）';

-- 用户消息收藏（引用消息方案）：仅存 msgId + 会话定位信息 + 内容快照；列表时优先回查权威消息，
-- 回查不到时回落到快照，保证「收藏内容永远可读」。
CREATE TABLE `msg_message_favorite`
(
  `id`                      bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`                 bigint NOT NULL COMMENT '收藏用户 ID',
  `msg_id`                  varchar(64) NOT NULL COMMENT '被收藏消息全局标识（引用 msg_message.msg_id，不做外键约束）',
  `peer_id`                 varchar(64) NOT NULL COMMENT '消息所属会话/对端标识（定位信息，用于回查消息）',
  `chat_type`               varchar(32) NOT NULL COMMENT '聊天类型，例如 private 或 group',
  `msg_type_snapshot`        varchar(32) DEFAULT NULL COMMENT '收藏时的消息类型快照（原消息删除后仍可渲染）',
  `content_snapshot`         text DEFAULT NULL COMMENT '收藏时的消息内容快照',
  `from_user_id_snapshot`    bigint DEFAULT NULL COMMENT '收藏时的发送者用户 ID 快照',
  `sender_username_snapshot` varchar(128) DEFAULT NULL COMMENT '收藏时的发送者用户名快照',
  `sent_at_snapshot`         datetime(3) DEFAULT NULL COMMENT '收藏时原消息的发送时间快照',
  `created_by`              bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`              datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '收藏时间',
  `updated_by`              bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`              datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_message_favorite_user_msg` (`user_id`, `msg_id`) COMMENT '同一用户重复收藏幂等约束',
  KEY `idx_msg_message_favorite_user_created` (`user_id`, `created_at`) COMMENT '用户收藏列表按收藏时间倒序分页'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户消息收藏（引用消息，正文回查权威消息表，缺失时回落快照）';

-- 好友通过自动私聊消息的持久化幂等记录；消息硬删除后仍保留，防止旧事件重放。
-- 原脚本使用 `utf8mb4_unicode_ci`，本次归并为全库统一的 `utf8mb4_0900_ai_ci`。
CREATE TABLE `msg_friend_accept_message`
(
  `id`         bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `request_id` bigint NOT NULL COMMENT '好友申请 ID',
  `event_id`   varchar(128) NOT NULL COMMENT '好友通过事件 ID',
  `status`     varchar(16) NOT NULL COMMENT '处理状态：PENDING/SUCCEEDED/SKIPPED',
  `msg_id`     varchar(128) DEFAULT NULL COMMENT '生成的正式消息 ID',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_friend_accept_message_request` (`request_id`) COMMENT '好友申请幂等约束',
  UNIQUE KEY `uk_msg_friend_accept_message_event` (`event_id`) COMMENT '好友通过事件幂等约束',
  KEY `idx_msg_friend_accept_message_status_updated` (`status`, `updated_at`) COMMENT '按状态与更新时间扫描待处理记录'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='好友通过自动私聊消息幂等记录';

-- 私密消息未读投影（不含内容，用于角标、跨设备同步与离线推送）。
-- 原主键为复合键 `(chat_type, user_id, msg_id)`，本次改为代理键 `id`，复合键降级为唯一索引
-- `uk_msg_secret_unread_chat_user_msg`：仍保证同一用户同一密聊会话同一消息只投影一次。
CREATE TABLE `msg_secret_unread`
(
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `chat_type`       varchar(32) NOT NULL COMMENT 'secret 或 secret_group',
  `conversation_id` bigint NOT NULL COMMENT '私密会话或私密群聊标识',
  `user_id`         bigint NOT NULL COMMENT '接收方用户标识',
  `msg_id`          varchar(64) NOT NULL COMMENT '消息标识（不保存密文）',
  `seq`             bigint NOT NULL COMMENT '会话内序号',
  `created_by`      bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '投影创建时间',
  `updated_by`      bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_secret_unread_chat_user_msg` (`chat_type`, `user_id`, `msg_id`) COMMENT '未读投影幂等约束（原复合主键）',
  KEY `idx_msg_secret_unread_user_chat_conversation_seq` (`user_id`, `chat_type`, `conversation_id`, `seq`) COMMENT '按用户与密聊会话游标统计未读'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私密消息未读投影（不含内容，用于角标、跨设备同步与离线推送）';

-- 用户「删除仅我」消息墓碑：仅对该用户隐藏，其余成员不受影响；跨重装保留。
--
-- 与 msg_message 的硬删除语义不同——本表只影响单个用户的可见性，消息本体与其它成员的可见性
-- 保持不变；也不做外键约束，避免消息硬删后阻塞。
CREATE TABLE `msg_user_deleted_message`
(
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`         bigint NOT NULL COMMENT '执行「删除仅我」的用户 ID',
  `msg_id`          varchar(64) NOT NULL COMMENT '被删除的消息全局标识（引用 msg_message.msg_id，不做外键约束）',
  `conversation_id` varchar(128) NOT NULL COMMENT '消息所属会话 ID（便于按会话清理与排查）',
  `chat_type`       varchar(32) NOT NULL COMMENT '会话类型，例如 PRIVATE / GROUP',
  `created_by`      bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '删除时间（UTC）',
  `updated_by`      bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_user_deleted_message_user_msg` (`user_id`, `msg_id`) COMMENT '同一用户重复删除幂等',
  KEY `idx_msg_user_deleted_message_user_conversation` (`user_id`, `conversation_id`) COMMENT '按用户+会话过滤',
  KEY `idx_msg_user_deleted_message_created` (`created_at`) COMMENT '按时间清理'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户「删除仅我」的消息墓碑（仅对该用户隐藏，跨重装保留）';

-- 会话清空标记（per-user）：记录某用户在某会话上的「清空到哪个时刻」。
--
-- 客户端每次同步都能拿到自己名下所有会话的清空时刻，据此删除本地早于该时刻的消息——
-- 离线、换端、重装都能自愈（依赖 WS 实时通知的方案在对方离线时会永久丢失）。
-- 清空是双方语义：清理发起方与对方各写一条，各自只影响自己的本地记录，不改变消息本体。
CREATE TABLE `msg_user_conversation_clear`
(
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`         bigint NOT NULL COMMENT '该标记归属的用户 ID',
  `conversation_id` varchar(128) NOT NULL COMMENT '被清空的会话 ID',
  `chat_type`       varchar(32) NOT NULL COMMENT '会话类型：private / group',
  `cleared_at`      datetime(3) NOT NULL COMMENT '清空时刻（UTC）；客户端删除早于该时刻的本地消息',
  `cleared_by`      bigint NOT NULL COMMENT '发起清空的用户 ID（用于排查与展示）',
  `created_by`      bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
  `updated_by`      bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_user_conversation_clear_user_conversation` (`user_id`, `conversation_id`) COMMENT '同一用户同一会话只保留最新清空时刻',
  KEY `idx_msg_user_conversation_clear_user_created` (`user_id`, `created_at`) COMMENT '按用户增量拉取'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话清空标记（per-user，供离线端在同步时自愈本地记录）';

-- 会话域（conversation）Flyway 基线：基线重建（批次 2）后本模块唯一的迁移文件，后续变更从 V2 起。
--
-- 合并来源（原 V1..V16 → 本文件；表结构取累计最终态，ALTER 全部折进建表语句）：
--   V1  conversation_group / conversation_group_member / conversation_outbox 建表
--   V2  conversation_group.allow_member_invite
--   V3  channel / channel_subscription 建表
--   V4  secret_chat 建表
--   V5  secret_chat 补 E2EE 字段（设备公钥 / 握手状态）与 created_by / updated_by
--   V6  channel / channel_subscription / secret_chat 的规范修复：表名规范化、主键与用户列改
--       bigint unsigned、补审计列、索引名补全表名 —— 已折进建表语句，RENAME/ALTER 不再需要
--   V7  conversation_channel.code（频道号）+ 存量回填 + 唯一键 / 名称搜索索引
--   V8  conversation_secret_group_chat / conversation_secret_group_member 建表
--   V9  conversation_secret_group_chat.anonymous_enabled
--   V10 conversation_secret_group_chat.pinned_msg_id / pinned_at
--   V11 conversation_secret_group_chat.invite_token / invite_expires_at
--   V12 conversation_secret_group_chat.owner_only_post
--   V13 conversation_secret_group_chat.name / announcement
--   V14 user_conversation_mute 建表
--   V15 conversation_group.allow_member_friend_request
--   V16 conversation_group.allow_member_view_account
--
-- 表名：本轮不改任何表名，也不登记新前缀。V6 已按“只增不改”把 channel → conversation_channel、
-- channel_subscription → conversation_channel_subscription、secret_chat → conversation_secret_chat
-- （java 侧 @TableName 同名）。基线直接按最终表名建表，因此 V3/V4 的旧表名与本文件的 RENAME 一并消失。
--
-- 审计列口径（DC-11：全表四列必须齐全）：
--   created_by / updated_by = bigint unsigned NOT NULL DEFAULT 0（0 = 系统 / 写入方未取到操作人）。
--   created_at / updated_at = datetime(3) NOT NULL。写入路径（MyBatis-Plus PO）未映射该列时带
--   DEFAULT CURRENT_TIMESTAMP(3)，否则 MySQL 8 严格模式下 INSERT 缺值会报 1364；已映射并显式赋值的
--   表不写 DEFAULT（与 common-mail / cst 基线同一取舍）：
--     conversation_channel_subscription : created_at / updated_at 未加 DEFAULT 也安全，但 V6 已给
--                                        DEFAULT CURRENT_TIMESTAMP(3) 兜底，故原样保留
--     user_conversation_mute            : created_at 保留 V14 的 DEFAULT，updated_at 未映射 → 带 DEFAULT
--     conversation_outbox               : created_at 由 append() 显式写入；updated_at 未映射 → 带 DEFAULT
--     conversation_group_member         : created_at / updated_at 均未映射 → 均带 DEFAULT
--     conversation_secret_group_member  : created_at / updated_at 均未映射 → 均带 DEFAULT
--   其余四张表（conversation_group / conversation_channel / conversation_secret_chat /
--   conversation_secret_group_chat）的 PO 已映射并显式赋值四个审计列，故只有 created_by / updated_by 用 DEFAULT 0。
--   created_by / updated_by 的写入路径：conversation_channel、conversation_channel_subscription、
--   conversation_secret_chat、conversation_secret_group_chat 的 PO 已赋值；conversation_group、
--   conversation_group_member、conversation_outbox、user_conversation_mute、
--   conversation_secret_group_member 的 PO 无对应字段，由 DEFAULT 0 兜底（后续补写入方时按列名直接赋值即可）。
--
-- 数据语句：原文件无 INSERT / 播种行；唯一的数据语句是 V7 的 conversation_channel.code 回填，
-- 只读写本表、无跨域依赖，故保留在文末（幂等：只更新 code 为空串的存量行，全新库为空操作）。
--
-- 索引名口径：idx_<完整表名>_<语义> / uk_<完整表名>_<语义>，全部加反引号。本轮补全/更名的索引：
--   idx_secret_group_chat_owner                 → idx_conversation_secret_group_chat_owner_user_id
--   uk_secret_group_member                      → uk_conversation_secret_group_member_group_user
--   idx_secret_group_member_user                → idx_conversation_secret_group_member_user_id
--   uk_user_conversation_mute                   → uk_user_conversation_mute_user_conversation
--   idx_user_conversation_mute_user             → idx_user_conversation_mute_user_id
-- （V6 已改好的 idx_conversation_channel_* / uk_conversation_channel_* / uk_conversation_secret_chat_* 保持原名。）
--
-- 主键：九张表原本已是 `id` bigint AUTO_INCREMENT，故无“业务键/复合键主键 → id”的转换，仅按规范补 unsigned；
-- 原业务唯一键一律降为 UNIQUE KEY 保留（uk_conversation_group_member_group_user 等），无一处丢失唯一性约束。

CREATE TABLE `conversation_group`
(
  `id`                         bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '群组标识',
  `name`                       varchar(128) NOT NULL COMMENT '群名称',
  `avatar`                     varchar(512) DEFAULT NULL COMMENT '群头像地址',
  `owner_id`                   bigint NOT NULL COMMENT '群主用户标识',
  `announcement`               text COMMENT '群公告',
  `max_members`                int NOT NULL DEFAULT 500 COMMENT '最大成员数',
  `allow_member_invite`        bit NOT NULL DEFAULT b'1' COMMENT '是否允许普通成员邀请用户',
  `allow_member_friend_request` bit NOT NULL DEFAULT b'1' COMMENT '允许群成员互加好友',
  `allow_member_view_account`  bit NOT NULL DEFAULT b'1' COMMENT '允许群成员查看他人账号',
  `status`                     varchar(32) NOT NULL COMMENT '群状态',
  `authorization_version`      bigint NOT NULL DEFAULT 1 COMMENT '授权版本',
  `created_by`                 bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人（0=系统）',
  `created_at`                 datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by`                 bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人（0=系统）',
  `updated_at`                 datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_conversation_group_owner_id` (`owner_id`) COMMENT '群主查询',
  KEY `idx_conversation_group_created_at` (`created_at`) COMMENT '按时间查询群组'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话域群组权威表';

CREATE TABLE `conversation_group_member`
(
  `id`                    bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '成员标识',
  `group_id`              bigint NOT NULL COMMENT '群组标识',
  `user_id`               bigint NOT NULL COMMENT '用户标识',
  `role`                  varchar(32) NOT NULL COMMENT '群成员角色',
  `nickname`              varchar(128) DEFAULT NULL COMMENT '群昵称',
  `is_muted`              bit NOT NULL DEFAULT b'0' COMMENT '是否禁言',
  `muted_until`           datetime(3) DEFAULT NULL COMMENT '禁言截止时间',
  `authorization_version` bigint NOT NULL DEFAULT 1 COMMENT '授权版本',
  `joined_at`             datetime(3) NOT NULL COMMENT '加入时间',
  `created_by`            bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人（0=系统，写入路径未映射）',
  `created_at`            datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（写入路径未映射，取落库时间）',
  `updated_by`            bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人（0=系统，写入路径未映射）',
  `updated_at`            datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间（写入路径未映射，取落库时间）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_group_member_group_user` (`group_id`, `user_id`) COMMENT '群成员唯一约束',
  KEY `idx_conversation_group_member_user_id` (`user_id`) COMMENT '用户群组查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话域群成员权威表';

CREATE TABLE `conversation_outbox`
(
  `id`           bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_id`     varchar(64) NOT NULL COMMENT '事件标识',
  `aggregate_id` varchar(64) NOT NULL COMMENT '聚合标识',
  `topic`        varchar(128) NOT NULL COMMENT '消息主题',
  `sharding_key` varchar(128) NOT NULL COMMENT '顺序分片键',
  `payload_json` json NOT NULL COMMENT '事件载荷',
  `published`    bit NOT NULL DEFAULT b'0' COMMENT '是否已发布',
  `published_at` datetime(3) DEFAULT NULL COMMENT '发布时间',
  `created_by`   bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人（0=系统，写入路径未映射）',
  `created_at`   datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by`   bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人（0=系统，写入路径未映射）',
  `updated_at`   datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间（写入路径未映射，取落库时间）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_outbox_event_id` (`event_id`) COMMENT '事件幂等约束',
  KEY `idx_conversation_outbox_pending` (`published`, `id`) COMMENT '待发布事件扫描'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话域授权事件待发布表';

CREATE TABLE `conversation_channel`
(
  `id`                  bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '频道标识',
  `code`                varchar(16) NOT NULL DEFAULT '' COMMENT '频道号（分享码，全局唯一）',
  `owner_id`            bigint unsigned NOT NULL COMMENT '所有者用户标识',
  `name`                varchar(128) NOT NULL COMMENT '频道名称',
  `avatar`              varchar(512) DEFAULT NULL COMMENT '频道头像地址',
  `announcement`        text COMMENT '频道公告',
  `discussion_group_id` bigint DEFAULT NULL COMMENT '关联讨论群组标识',
  `status`              varchar(32) NOT NULL COMMENT '频道状态',
  `created_by`          bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at`          datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by`          bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at`          datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_channel_code` (`code`) COMMENT '频道号唯一约束',
  KEY `idx_conversation_channel_owner_id` (`owner_id`) COMMENT '所有者查询',
  KEY `idx_conversation_channel_name` (`name`) COMMENT '频道名称搜索'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道权威表';

CREATE TABLE `conversation_channel_subscription`
(
  `id`             bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '订阅标识',
  `channel_id`     bigint unsigned NOT NULL COMMENT '频道标识',
  `user_id`        bigint unsigned NOT NULL COMMENT '订阅用户标识',
  `notify_setting` varchar(32) NOT NULL DEFAULT 'default' COMMENT '通知设置',
  `joined_at`      datetime(3) NOT NULL COMMENT '订阅时间',
  `created_by`     bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`     bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_channel_subscription_channel_user` (`channel_id`, `user_id`) COMMENT '订阅唯一约束',
  KEY `idx_conversation_channel_subscription_user_id` (`user_id`) COMMENT '用户订阅查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道订阅权威表';

CREATE TABLE `conversation_secret_chat`
(
  `id`                 bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '私密会话标识',
  `user_a`             bigint unsigned NOT NULL COMMENT '参与者 A（规范化较小用户）',
  `user_b`             bigint unsigned NOT NULL COMMENT '参与者 B（规范化较大用户）',
  `status`             varchar(32) NOT NULL COMMENT '会话状态（handshake/ready/closed）',
  `safe_code`          varchar(128) DEFAULT NULL COMMENT '安全码指纹（形态先行展示用）',
  `destroy_policy`     varchar(32) NOT NULL DEFAULT 'off' COMMENT '定时销毁策略',
  `user_a_public_key`  varchar(512) DEFAULT NULL COMMENT '参与者A设备公钥（客户端生成，服务端仅存储转发）',
  `user_b_public_key`  varchar(512) DEFAULT NULL COMMENT '参与者B设备公钥',
  `handshake_state`    varchar(32) NOT NULL DEFAULT 'pending' COMMENT '握手状态(pending/ready)',
  `created_by`         bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at`         datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by`         bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at`         datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_secret_chat_users` (`user_a`, `user_b`) COMMENT '一对用户唯一私密会话',
  KEY `idx_conversation_secret_chat_user_b` (`user_b`) COMMENT '按参与者 B 查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私密聊天会话权威表';

CREATE TABLE `conversation_secret_group_chat`
(
  `id`                bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '私密群聊标识',
  `owner_user_id`     bigint NOT NULL COMMENT '群主用户标识',
  `status`            varchar(32) NOT NULL DEFAULT 'active' COMMENT '状态(active/closed)',
  `name`              varchar(128) DEFAULT NULL COMMENT '群名称',
  `announcement`      varchar(2000) DEFAULT NULL COMMENT '群公告',
  `safe_code`         varchar(128) DEFAULT NULL COMMENT '群安全码指纹（排序后成员公钥 SHA-256）',
  `destroy_policy`    varchar(32) NOT NULL DEFAULT 'off' COMMENT '定时销毁策略(off/30s/5m/1h/1d)',
  `anonymous_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '匿名发言开关(0关/1开)',
  `pinned_msg_id`     varchar(64) DEFAULT NULL COMMENT '置顶消息 msgId',
  `pinned_at`         datetime(3) DEFAULT NULL COMMENT '置顶时间',
  `invite_token`      varchar(64) DEFAULT NULL COMMENT '邀请令牌（群主生成）',
  `invite_expires_at` datetime(3) DEFAULT NULL COMMENT '邀请过期时间',
  `owner_only_post`   tinyint(1) NOT NULL DEFAULT 0 COMMENT '仅群主可发言开关(0关/1开)',
  `created_by`        bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at`        datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by`        bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at`        datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_conversation_secret_group_chat_owner_user_id` (`owner_user_id`) COMMENT '群主查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私密群聊会话权威表';

CREATE TABLE `conversation_secret_group_member`
(
  `id`                bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '成员标识',
  `secret_group_id`   bigint NOT NULL COMMENT '私密群聊标识',
  `user_id`           bigint NOT NULL COMMENT '成员用户标识',
  `device_public_key` varchar(512) DEFAULT NULL COMMENT '成员设备公钥（客户端生成，服务端仅存储转发）',
  `joined_at`         datetime(3) NOT NULL COMMENT '加入时间',
  `created_by`        bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人（0=系统，写入路径未映射）',
  `created_at`        datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（写入路径未映射，取落库时间）',
  `updated_by`        bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人（0=系统，写入路径未映射）',
  `updated_at`        datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间（写入路径未映射，取落库时间）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_secret_group_member_group_user` (`secret_group_id`, `user_id`) COMMENT '私密群成员唯一约束',
  KEY `idx_conversation_secret_group_member_user_id` (`user_id`) COMMENT '成员用户查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私密群聊成员权威表';

CREATE TABLE `user_conversation_mute`
(
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`         bigint unsigned NOT NULL COMMENT '用户 ID',
  `conversation_id` varchar(128) NOT NULL COMMENT '会话标识（conv:private/group/channel 或 secret/secret_group）',
  `created_by`      bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人（0=系统，写入路径未映射）',
  `created_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`      bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人（0=系统，写入路径未映射）',
  `updated_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间（写入路径未映射，取落库时间）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_conversation_mute_user_conversation` (`user_id`, `conversation_id`) COMMENT '用户会话免打扰唯一约束',
  KEY `idx_user_conversation_mute_user_id` (`user_id`) COMMENT '用户免打扰查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户会话免打扰';

-- V7 存量频道号回填：以 LPAD(id) 生成可读频道号（不足 6 位补 0），保证唯一。
-- 幂等：只更新 code 为空串的存量行；全新库无行，等同于空操作。
UPDATE `conversation_channel` SET `code` = LPAD(`id`, 6, '0') WHERE `code` = '';

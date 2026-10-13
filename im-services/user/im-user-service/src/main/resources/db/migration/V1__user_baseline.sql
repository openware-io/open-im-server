-- 用户域基线（im-user-service）：本文件是**唯一**的 Flyway 迁移，重建后下一个变更从 V2 开始。
--
-- 基线重建（批次 2）：把原 V1..V24 共 24 个迁移合并为本文件，取**累计最终态**——
-- 所有后续 ALTER TABLE 的结果直接写进 CREATE TABLE，被删除的表（user_point_account /
-- user_point_ledger / user_coin_account / user_coin_ledger）不再出现，数据订正只保留最终语义。
--
-- 规范化内容：
--   1. 表名与列名全部加反引号；统一 ENGINE=InnoDB / utf8mb4 / utf8mb4_0900_ai_ci 与表注释；
--   2. 补齐 DC-11 的 4 个审计列（created_by / updated_by / created_at / updated_at）；
--   3. 索引名统一为 idx_<完整表名>_<语义> / uk_<完整表名>_<语义>；
--   4. 时间列统一 datetime(3)；
--   5. 业务主键一律改为 `id` bigint unsigned AUTO_INCREMENT + 原业务键上的 UNIQUE KEY。
--
-- ⚠️ open_application / open_application_scope / open_user_authorization 三张表**保持 open_ 表名不变**：
--    open_ 前缀属于挂起的架构决策，本次不重命名、不登记新前缀（门禁会继续报 unregistered prefix）。
--
-- 数据语句说明（均在末尾，且幂等）：
--   1. 原 V1 植入的默认管理员 admin/888999 在 V8 被删除，最终态**没有**该行，故基线不再植入；
--   2. 原 V17 回填「星空AI（自营）」被 V18 撤销，最终态**不保留**任何编造主体名，故两条语句都不折入；
--   3. 原 V23 删除 a380 两个客户端的回调白名单并把 callback_url 指向 unconfigured.invalid
--      （回调地址是**环境事实**，不得从历史环境带入新部署），已折入；
--   4. 原 V24 把 saas-ktv 的回调地址改为 openchat://oauth/callback，已折入；
--   5. V22 的注册表回填只读写本模块自己的表（open_* → user_oidc_*），不属跨域语句，已折入并保留 ON DUPLICATE KEY UPDATE。

-- =====================================================================================
-- 表结构
-- =====================================================================================

CREATE TABLE `user` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` varchar(64) NOT NULL COMMENT '登录用户名，全局唯一',
  `nickname` varchar(128) NOT NULL DEFAULT '' COMMENT '显示昵称',
  `avatar` varchar(512) NOT NULL DEFAULT '' COMMENT '头像 URL',
  `password` varchar(256) NOT NULL COMMENT '密码哈希',
  `email` varchar(256) DEFAULT NULL COMMENT '邮箱',
  `phone` varchar(30) DEFAULT NULL COMMENT '手机号',
  `signature` varchar(512) NOT NULL DEFAULT '' COMMENT '个性签名',
  `status` enum('active','muted','disabled') NOT NULL DEFAULT 'active' COMMENT '账号状态(active/muted/disabled)',
  -- 列序与累计态一致：V6 用 AFTER `status` 插入自毁三列，故 `status_version` / `role` 顺延到其后。
  `self_destruct_policy` varchar(16) NOT NULL DEFAULT 'off' COMMENT '自毁策略 off/1mo/3mo/6mo/1yr',
  `self_destruct_at` datetime(3) DEFAULT NULL COMMENT '自毁截止时间（到期硬删账号与数据）',
  `last_login_at` datetime(3) DEFAULT NULL COMMENT '最近登录时间',
  `status_version` bigint unsigned NOT NULL DEFAULT '1' COMMENT '用户状态版本',
  `role` enum('user','admin') NOT NULL DEFAULT 'user' COMMENT '用户角色',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`),
  KEY `idx_user_phone` (`phone`),
  KEY `idx_user_email` (`email`),
  KEY `idx_user_self_destruct_at` (`self_destruct_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户';

CREATE TABLE `user_device_token` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户 ID',
  `token` varchar(512) NOT NULL COMMENT '推送令牌',
  `push_provider` enum('apns','fcm','jpush') NOT NULL DEFAULT 'fcm' COMMENT '推送渠道',
  `platform` enum('android','ios','web','windows','macos') NOT NULL COMMENT '客户端平台',
  `device_id` varchar(128) DEFAULT NULL COMMENT '设备唯一标识',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_device_token_provider_token` (`push_provider`, `token`),
  KEY `idx_user_device_token_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户设备推送令牌';

CREATE TABLE `user_friend_request` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `from_user_id` bigint unsigned NOT NULL COMMENT '发起方用户 ID',
  `to_user_id` bigint unsigned NOT NULL COMMENT '接收方用户 ID',
  `message` varchar(256) NOT NULL DEFAULT '' COMMENT '好友申请附言',
  `status` enum('pending','accepted','rejected') NOT NULL DEFAULT 'pending' COMMENT '申请状态',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_friend_request_to_status` (`to_user_id`, `status`),
  KEY `idx_user_friend_request_from` (`from_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户好友申请';

CREATE TABLE `user_friend` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '关系持有者用户 ID',
  `friend_id` bigint unsigned NOT NULL COMMENT '好友用户 ID',
  `remark` varchar(128) NOT NULL DEFAULT '' COMMENT '好友备注',
  `group_name` varchar(64) NOT NULL DEFAULT '默认分组' COMMENT '好友分组',
  `status` enum('normal','blocked') NOT NULL DEFAULT 'normal' COMMENT '关系状态',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_friend_user_friend` (`user_id`, `friend_id`),
  KEY `idx_user_friend_friend` (`friend_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户好友关系';

CREATE TABLE `user_sticker` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户 ID',
  `url` varchar(500) NOT NULL COMMENT '表情图片 URL',
  `thumbnail` varchar(500) DEFAULT NULL COMMENT '缩略图 URL',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序权重',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_sticker_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表情';

-- 主键转换：原业务主键 `user_id` → `id` 自增主键 + `uk_user_sticker_quota_user`。
CREATE TABLE `user_sticker_quota` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户 ID',
  `sticker_count` int unsigned NOT NULL DEFAULT '0' COMMENT '已占用贴纸名额',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_sticker_quota_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户贴纸名额';

CREATE TABLE `user_outbox` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_id` varchar(64) NOT NULL COMMENT '事件幂等键',
  `aggregate_type` varchar(64) NOT NULL COMMENT '聚合类型',
  `aggregate_id` varchar(64) NOT NULL COMMENT '聚合标识',
  `topic` varchar(128) NOT NULL COMMENT '消息主题',
  `sharding_key` varchar(128) NOT NULL COMMENT '顺序分片键',
  `payload_json` json NOT NULL COMMENT '事件载荷',
  `published` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已发布',
  `published_at` datetime(3) DEFAULT NULL COMMENT '发布时间',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_outbox_event` (`event_id`),
  KEY `idx_user_outbox_pending` (`published`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户域待发布事件';

CREATE TABLE `user_admin_status_operation` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键',
  `user_id` bigint unsigned NOT NULL COMMENT '目标用户 ID',
  `expected_version` bigint unsigned NOT NULL COMMENT '请求状态版本',
  `previous_status` varchar(16) NOT NULL COMMENT '变更前状态',
  `current_status` varchar(16) NOT NULL COMMENT '变更后状态',
  `status_version` bigint unsigned NOT NULL COMMENT '变更后状态版本',
  `operator_id` bigint unsigned NOT NULL COMMENT '操作人 ID',
  `reason` varchar(512) NOT NULL COMMENT '操作理由',
  `correlation_id` varchar(128) NOT NULL COMMENT '关联 ID',
  `request_version` int unsigned NOT NULL COMMENT '请求契约版本',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_admin_status_operation_idempotency` (`idempotency_key`),
  KEY `idx_user_admin_status_operation_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户管理状态操作审计';

CREATE TABLE `user_device_key` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '设备密钥标识',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户标识',
  `device_id` varchar(128) NOT NULL COMMENT '设备标识',
  `public_key` varchar(512) NOT NULL COMMENT '设备公钥（客户端生成，服务端仅存储与转发）',
  `status` varchar(32) NOT NULL DEFAULT 'active' COMMENT '设备密钥状态',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_device_key_user_device` (`user_id`, `device_id`),
  KEY `idx_user_device_key_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户设备公钥权威表';

-- 主键转换：原业务主键 `user_id` → `id` 自增主键 + `uk_user_notification_setting_user`。
CREATE TABLE `user_notification_setting` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '用户标识（与 user.id 一致）',
  `notify_private` tinyint(1) NOT NULL DEFAULT 1 COMMENT '私聊离线推送',
  `notify_group` tinyint(1) NOT NULL DEFAULT 1 COMMENT '群聊离线推送',
  `notify_channel` tinyint(1) NOT NULL DEFAULT 1 COMMENT '频道离线推送',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_notification_setting_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户离线推送通知设置权威表';

-- 主键转换：原业务主键 `user_id` → `id` 自增主键 + `uk_user_privacy_setting_user`。
CREATE TABLE `user_privacy_setting` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '用户标识（与 user.id 一致）',
  `allow_group_friend_request` tinyint NOT NULL DEFAULT 1 COMMENT '允许通过群聊添加我为好友',
  `hide_group_member_info` tinyint NOT NULL DEFAULT 1 COMMENT '隐藏非好友群成员用户名头像',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_privacy_setting_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户隐私设置权威表';

CREATE TABLE `user_account_cancellation` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT 'IM 用户 id',
  `username` varchar(64) NOT NULL COMMENT '申请时的用户名快照',
  `nickname` varchar(128) DEFAULT NULL COMMENT '申请时的昵称快照',
  `phone` varchar(32) DEFAULT NULL COMMENT '申请时的手机号快照',
  `email` varchar(256) DEFAULT NULL COMMENT '申请时的邮箱快照',
  `status` varchar(20) NOT NULL COMMENT '状态：pending/processing/completed/failed',
  `status_token_hash` varchar(64) NOT NULL COMMENT '状态查询令牌的 SHA-256 摘要',
  `source` varchar(20) NOT NULL COMMENT '来源：web/app/admin',
  `completed_steps` varchar(512) DEFAULT NULL COMMENT '已完成的删除步骤编码，逗号分隔',
  `requested_ip` varchar(64) DEFAULT NULL COMMENT '申请来源 IP',
  `requested_at` datetime(3) NOT NULL COMMENT '申请时间',
  `processing_at` datetime(3) DEFAULT NULL COMMENT '开始处理时间',
  `completed_at` datetime(3) DEFAULT NULL COMMENT '完成时间',
  `failed_reason` varchar(255) DEFAULT NULL COMMENT '失败原因',
  `row_version` bigint unsigned NOT NULL DEFAULT '1' COMMENT '乐观锁版本',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_account_cancellation_user` (`user_id`),
  KEY `idx_user_account_cancellation_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号注销申请表';

CREATE TABLE `user_account_cancellation_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `cancellation_id` bigint unsigned NOT NULL COMMENT '注销申请 id',
  `user_id` bigint unsigned NOT NULL COMMENT 'IM 用户 id',
  `action` varchar(32) NOT NULL COMMENT '动作：requested/processing/completed/failed',
  `detail` varchar(512) DEFAULT NULL COMMENT '详情',
  `operator_type` varchar(16) NOT NULL COMMENT '操作方：USER/SYSTEM/ADMIN',
  `operator_id` bigint unsigned DEFAULT NULL COMMENT '操作人 id（ADMIN 时）',
  `ip` varchar(64) DEFAULT NULL COMMENT '来源 IP',
  `occurred_at` datetime(3) NOT NULL COMMENT '发生时间',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_account_cancellation_log_cid` (`cancellation_id`),
  KEY `idx_user_account_cancellation_log_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号注销申请审计日志表';

CREATE TABLE `user_security_question` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT 'IM 用户 id',
  `question` varchar(128) NOT NULL COMMENT '密保问题（预设或自定义）',
  `answer_hash` varchar(256) NOT NULL COMMENT '密保答案哈希（bcrypt），不落明文',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_security_question_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户密保问题与答案';

CREATE TABLE `user_device_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT 'IM 用户 id',
  `device_id` varchar(128) NOT NULL COMMENT '设备唯一标识（客户端生成）',
  `device_type` varchar(32) DEFAULT NULL COMMENT '设备类型：mobile/desktop/tablet/web',
  `device_name` varchar(128) DEFAULT NULL COMMENT '设备名称（如 iPhone 15）',
  `login_ip` varchar(64) DEFAULT NULL COMMENT '最近一次登录来源 IP',
  `login_method` varchar(32) NOT NULL COMMENT '登录方式：password/verification_code/qr_code/sso',
  `last_active_at` datetime(3) NOT NULL COMMENT '最后活跃时间',
  `last_active_ip` varchar(64) DEFAULT NULL COMMENT '最后活跃 IP',
  `status` varchar(16) NOT NULL DEFAULT 'active' COMMENT '状态：active/kicked/logout',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_device_session_user_device` (`user_id`, `device_id`),
  KEY `idx_user_device_session_user_status` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户设备登录会话';

-- ⚠️ open_ 前缀**保持不变**（架构决策挂起）：应用登记。累计态 = V11 + V16（可空密钥/驳回与审核字段）+ V17（主体名）。
CREATE TABLE `open_application` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `app_id` varchar(64) NOT NULL COMMENT '应用唯一标识（对外 appId）',
  `app_name` varchar(128) NOT NULL COMMENT '应用名称',
  `subject_name` varchar(128) NULL COMMENT '主体名称（接入方/业务方）',
  `app_type` varchar(32) NOT NULL COMMENT '应用类型，如 THIRD_PARTY',
  `callback_url` varchar(512) NOT NULL COMMENT 'OAuth 回调地址',
  `app_secret_hash` varchar(64) NULL COMMENT '应用密钥 SHA-256 哈希（审核通过后分配）',
  `status` varchar(32) NOT NULL COMMENT '应用状态：PENDING/APPROVED/SUSPENDED',
  `reject_reason` varchar(512) NULL COMMENT '驳回原因',
  `reviewed_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '审核人 ID，0 表示系统',
  `reviewed_at` datetime(3) NULL COMMENT '审核时间',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_open_application_app_id` (`app_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='开放平台应用';

-- ⚠️ open_ 前缀**保持不变**（架构决策挂起）：应用授权范围。补齐 DC-11 的 4 个审计列（写方为应用审核流程，无操作人上下文，取 DEFAULT 0 / 数据库当前时间）。
CREATE TABLE `open_application_scope` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `application_id` bigint unsigned NOT NULL COMMENT '所属应用 ID',
  `scope` varchar(64) NOT NULL COMMENT '授权范围，如 profile.basic',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_open_application_scope_app` (`application_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='开放平台应用授权范围';

-- ⚠️ open_ 前缀**保持不变**（架构决策挂起）：用户级授权。补齐 DC-11 的 4 个审计列（写方为 OAuth 授权流程，无操作人上下文，取 DEFAULT 0 / 数据库当前时间）。
CREATE TABLE `open_user_authorization` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `application_id` bigint unsigned NOT NULL COMMENT '所属应用 ID',
  `user_id` bigint unsigned NOT NULL COMMENT '授权用户 ID',
  `open_id` varchar(64) NOT NULL COMMENT '对应用暴露的用户标识（由 userId 派生，im_ 前缀）',
  `scope` varchar(512) NOT NULL COMMENT '已授权范围（空格分隔）',
  `status` varchar(32) NOT NULL COMMENT '授权状态：ACTIVE/REVOKED',
  `authorized_at` datetime(3) NOT NULL COMMENT '授权时间',
  `revoked_at` datetime(3) NULL COMMENT '撤销时间',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_open_user_authorization_app_user` (`application_id`, `user_id`),
  KEY `idx_open_user_authorization_open_id` (`open_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='开放平台用户授权';

CREATE TABLE `user_oidc_client` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `client_id` varchar(128) NOT NULL COMMENT 'OIDC client identifier',
  `client_name` varchar(128) NOT NULL COMMENT '展示名称',
  `client_type` varchar(32) NOT NULL COMMENT 'PUBLIC or CONFIDENTIAL',
  `token_endpoint_auth_method` varchar(64) NOT NULL COMMENT 'none or client_secret_basic',
  `status` varchar(32) NOT NULL COMMENT 'ACTIVE or REVOKED',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_oidc_client_client_id` (`client_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OIDC客户端注册';

-- 索引改名：uk_user_oidc_redirect_client_uri → uk_user_oidc_redirect_uri_client_uri（补全完整表名）。补齐审计列。
CREATE TABLE `user_oidc_redirect_uri` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `client_id` varchar(128) NOT NULL COMMENT 'OIDC client identifier',
  `redirect_uri` varchar(512) NOT NULL COMMENT '精确回调地址',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_oidc_redirect_uri_client_uri` (`client_id`, `redirect_uri`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OIDC回调白名单';

-- 补齐审计列。本表只有迁移期回填（无运行期写方）。
CREATE TABLE `user_oidc_scope` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `client_id` varchar(128) NOT NULL COMMENT 'OIDC client identifier',
  `scope` varchar(128) NOT NULL COMMENT '授权范围',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_oidc_scope_client_scope` (`client_id`, `scope`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OIDC客户端授权范围';

-- 补齐审计列。本表只有迁移期回填（无运行期写方）。
CREATE TABLE `user_oidc_consent` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `client_id` varchar(128) NOT NULL COMMENT 'OIDC client identifier',
  `user_id` bigint unsigned NOT NULL COMMENT '用户标识',
  `scope` varchar(1024) NOT NULL COMMENT '最终批准范围',
  `status` varchar(32) NOT NULL COMMENT 'ACTIVE or REVOKED',
  `authorized_at` datetime(3) NOT NULL COMMENT '授权时间',
  `revoked_at` datetime(3) NULL COMMENT '撤销时间',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_oidc_consent_client_user` (`client_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OIDC用户同意记录';

-- 补齐审计列。本表目前无写方（预留）。
CREATE TABLE `user_oidc_token_family` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `family_id` varchar(128) NOT NULL COMMENT 'refresh token family',
  `client_id` varchar(128) NOT NULL COMMENT 'OIDC client identifier',
  `user_id` bigint unsigned NOT NULL COMMENT '用户标识',
  `status` varchar(32) NOT NULL COMMENT 'ACTIVE or REVOKED',
  `revoked_at` datetime(3) NULL COMMENT '撤销时间',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_oidc_token_family_family` (`family_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OIDC刷新令牌族';

-- =====================================================================================
-- 基线数据（累计最终态）
-- =====================================================================================

-- 开放平台种子应用。
-- 说明：原 V1 植入的默认管理员 admin/888999 已在 V8 被删除（安全修复），最终态无该行，故基线不再植入；
--       新库首管理员由运维通过受管流程创建并强制首登改密。
INSERT INTO `open_application`
  (`app_id`, `app_name`, `app_type`, `callback_url`, `app_secret_hash`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('saas-ktv', 'SaaS KTV 业务', 'THIRD_PARTY', 'gvchat://oauth/callback',
   '4b444ccf84dc297e02fb3c5b3d32a22252143765c1bfdb0cb26a99963070f18a', 'APPROVED', 0, CURRENT_TIMESTAMP(3), 0, CURRENT_TIMESTAMP(3));

INSERT INTO `open_application`
  (`app_id`, `app_name`, `app_type`, `callback_url`, `app_secret_hash`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('saas-a380-c', 'A380 更多服务', 'THIRD_PARTY', 'https://miniservice.dev.example.com/a380/',
   '353f718b81b455321e8f7a3e16c3282f5c74c2474ad5f6502e7d8b4a849f8eb5', 'APPROVED', 0, CURRENT_TIMESTAMP(3), 0, CURRENT_TIMESTAMP(3)),
  ('saas-a380-h5', 'A380 商户端', 'THIRD_PARTY', 'https://miniservice.dev.example.com/b/',
   '353f718b81b455321e8f7a3e16c3282f5c74c2474ad5f6502e7d8b4a849f8eb5', 'APPROVED', 0, CURRENT_TIMESTAMP(3), 0, CURRENT_TIMESTAMP(3));

INSERT INTO `open_application_scope` (`application_id`, `scope`)
SELECT `id`, 'profile.basic' FROM `open_application` WHERE `app_id` = 'saas-ktv';

INSERT INTO `open_application_scope` (`application_id`, `scope`)
SELECT `id`, 'profile.phone' FROM `open_application` WHERE `app_id` = 'saas-ktv';

INSERT INTO `open_application_scope` (`application_id`, `scope`)
SELECT `id`, 'profile.basic' FROM `open_application` a WHERE a.`app_id` IN ('saas-a380-c', 'saas-a380-h5');

-- 三个客户端均为公开客户端（PKCE，不携带密钥）。
UPDATE `open_application`
   SET `app_type` = 'PUBLIC', `app_secret_hash` = ''
 WHERE `app_id` IN ('saas-ktv', 'saas-a380-c', 'saas-a380-h5');

INSERT INTO `open_application_scope` (`application_id`, `scope`)
SELECT `id`, 'openid' FROM `open_application` a
 WHERE a.`app_id` IN ('saas-ktv', 'saas-a380-c', 'saas-a380-h5');

-- 回调地址是环境事实：不得把历史环境的回调地址带入新部署，两个 A380 客户端置为占位地址。
-- 注意：白名单（user_oidc_redirect_uri）**不跟随**这次改动——白名单是当时登记回调的冻结快照，
-- saas-ktv 也保持 gvchat 回调（见下一条 UPDATE 只改 open_application.callback_url）。
UPDATE `open_application`
SET `callback_url` = CONCAT('https://unconfigured.invalid/oidc/', `app_id`)
WHERE `app_id` IN ('saas-a380-c', 'saas-a380-h5');

-- OIDC 注册表回填：环境部署负责回调白名单，这里只回填结构化客户端事实（幂等）。
INSERT INTO `user_oidc_client`
  (`client_id`, `client_name`, `client_type`, `token_endpoint_auth_method`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT `app_id`, `app_name`,
       CASE WHEN `app_type` = 'PUBLIC' THEN 'PUBLIC' ELSE 'CONFIDENTIAL' END,
       CASE WHEN `app_type` = 'PUBLIC' THEN 'none' ELSE 'client_secret_basic' END,
       CASE WHEN `status` = 'APPROVED' THEN 'ACTIVE' ELSE 'REVOKED' END,
       0, CURRENT_TIMESTAMP(3), 0, CURRENT_TIMESTAMP(3)
FROM `open_application`
WHERE `app_id` IN ('saas-ktv', 'saas-a380-c', 'saas-a380-h5')
ON DUPLICATE KEY UPDATE
  `client_name` = VALUES(`client_name`), `client_type` = VALUES(`client_type`),
  `token_endpoint_auth_method` = VALUES(`token_endpoint_auth_method`),
  `status` = VALUES(`status`), `updated_at` = VALUES(`updated_at`);

-- 白名单按**登记时**的 callback_url 冻结（此后 open_application 的回调改名不再回灌白名单），
-- 随后两个 A380 客户端被移出白名单（其回调地址是环境事实）——两步与原迁移一致，
-- 因此最终只留下 saas-ktv 一行（其自增 id 是插入顺序的产物，无业务含义）。
INSERT INTO `user_oidc_redirect_uri` (`client_id`, `redirect_uri`)
SELECT `app_id`, `callback_url`
FROM `open_application`
WHERE `app_id` IN ('saas-ktv', 'saas-a380-c', 'saas-a380-h5')
ON DUPLICATE KEY UPDATE `redirect_uri` = VALUES(`redirect_uri`);

DELETE FROM `user_oidc_redirect_uri`
WHERE `client_id` IN ('saas-a380-c', 'saas-a380-h5');

-- saas-ktv 回调地址改名：gvchat://oauth/callback → openchat://oauth/callback。
UPDATE `open_application`
SET `callback_url` = 'openchat://oauth/callback'
WHERE `app_id` = 'saas-ktv'
  AND `callback_url` = 'gvchat://oauth/callback';

-- 范围回填：客户端 → 授权范围。（本表自增 id 取决于插入顺序，属实现细节；业务键是 (client_id, scope)。）
INSERT INTO `user_oidc_scope` (`client_id`, `scope`)
SELECT a.`app_id`, s.`scope`
FROM `open_application` a
JOIN `open_application_scope` s ON s.`application_id` = a.`id`
WHERE a.`app_id` IN ('saas-ktv', 'saas-a380-c', 'saas-a380-h5')
ON DUPLICATE KEY UPDATE `scope` = VALUES(`scope`);

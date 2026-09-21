-- 账号注销申请 + 审计日志：官网/App 提交注销申请后异步硬删账号与关联数据，并记录审计日志统一管理。

CREATE TABLE IF NOT EXISTS `user_account_cancellation`
(
  `id`                bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`           bigint unsigned NOT NULL COMMENT 'IM 用户 id',
  `username`          varchar(64)  NOT NULL COMMENT '申请时的用户名快照',
  `nickname`          varchar(128) DEFAULT NULL COMMENT '申请时的昵称快照',
  `phone`             varchar(32)  DEFAULT NULL COMMENT '申请时的手机号快照',
  `email`             varchar(256) DEFAULT NULL COMMENT '申请时的邮箱快照',
  `status`            varchar(20)  NOT NULL COMMENT '状态：pending/processing/completed/failed',
  `status_token_hash` varchar(64)  NOT NULL COMMENT '状态查询令牌的 SHA-256 摘要',
  `source`            varchar(20)  NOT NULL COMMENT '来源：web/app/admin',
  `completed_steps`   varchar(512) DEFAULT NULL COMMENT '已完成的删除步骤编码，逗号分隔',
  `requested_ip`      varchar(64)  DEFAULT NULL COMMENT '申请来源 IP',
  `requested_at`      datetime(3) NOT NULL COMMENT '申请时间',
  `processing_at`     datetime(3) DEFAULT NULL COMMENT '开始处理时间',
  `completed_at`      datetime(3) DEFAULT NULL COMMENT '完成时间',
  `failed_reason`     varchar(255) DEFAULT NULL COMMENT '失败原因',
  `row_version`       bigint unsigned NOT NULL DEFAULT '1' COMMENT '乐观锁版本',
  `created_by`        bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`        datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`        bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`        datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_account_cancellation_user` (`user_id`),
  KEY `idx_user_account_cancellation_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号注销申请表';

CREATE TABLE IF NOT EXISTS `user_account_cancellation_log`
(
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `cancellation_id` bigint unsigned NOT NULL COMMENT '注销申请 id',
  `user_id`         bigint unsigned NOT NULL COMMENT 'IM 用户 id',
  `action`          varchar(32)  NOT NULL COMMENT '动作：requested/processing/completed/failed',
  `detail`          varchar(512) DEFAULT NULL COMMENT '详情',
  `operator_type`   varchar(16)  NOT NULL COMMENT '操作方：USER/SYSTEM/ADMIN',
  `operator_id`     bigint unsigned DEFAULT NULL COMMENT '操作人 id（ADMIN 时）',
  `ip`              varchar(64)  DEFAULT NULL COMMENT '来源 IP',
  `occurred_at`     datetime(3) NOT NULL COMMENT '发生时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_account_cancellation_log_cid` (`cancellation_id`),
  KEY `idx_user_account_cancellation_log_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号注销申请审计日志表';

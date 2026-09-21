-- 多端登录设备会话：记录每台设备的登录 IP、登录方式、设备类型/名称与最后活跃时间（参考百度网盘登录账号管理）。

CREATE TABLE IF NOT EXISTS `user_device_session`
(
  `id`             bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`        bigint unsigned NOT NULL COMMENT 'IM 用户 id',
  `device_id`      varchar(128) NOT NULL COMMENT '设备唯一标识（客户端生成）',
  `device_type`    varchar(32)  DEFAULT NULL COMMENT '设备类型：mobile/desktop/tablet/web',
  `device_name`    varchar(128) DEFAULT NULL COMMENT '设备名称（如 iPhone 15）',
  `login_ip`       varchar(64)  DEFAULT NULL COMMENT '最近一次登录来源 IP',
  `login_method`   varchar(32)  NOT NULL COMMENT '登录方式：password/verification_code/qr_code/sso',
  `last_active_at` datetime(3) NOT NULL COMMENT '最后活跃时间',
  `last_active_ip` varchar(64)  DEFAULT NULL COMMENT '最后活跃 IP',
  `status`         varchar(16)  NOT NULL DEFAULT 'active' COMMENT '状态：active/kicked/logout',
  `created_by`     bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`     bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_device_session_user_device` (`user_id`, `device_id`),
  KEY `idx_user_device_session_user_status` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户设备登录会话';

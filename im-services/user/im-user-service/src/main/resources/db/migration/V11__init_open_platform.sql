-- IM 开放平台（OAuth 2.0 Provider）初始化：
-- open_application 应用登记、open_application_scope 应用授权范围、open_user_authorization 用户级授权。
-- 应用密钥仅存 SHA-256 哈希（禁止明文落库）；种子应用 saas-ktv 注册即 APPROVED。
CREATE TABLE `open_application`
(
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `app_id`          varchar(64)  NOT NULL COMMENT '应用唯一标识（对外 appId）',
  `app_name`        varchar(128) NOT NULL COMMENT '应用名称',
  `app_type`        varchar(32)  NOT NULL COMMENT '应用类型，如 THIRD_PARTY',
  `callback_url`    varchar(512) NOT NULL COMMENT 'OAuth 回调地址',
  `app_secret_hash` varchar(64)  NOT NULL COMMENT '应用密钥 SHA-256 哈希',
  `status`          varchar(32)  NOT NULL COMMENT '应用状态：PENDING/APPROVED/SUSPENDED',
  `created_by`      bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`      bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_open_application_app_id` (`app_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='开放平台应用';

CREATE TABLE `open_application_scope`
(
  `id`             bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `application_id` bigint unsigned NOT NULL COMMENT '所属应用 ID',
  `scope`          varchar(64) NOT NULL COMMENT '授权范围，如 profile.basic',
  PRIMARY KEY (`id`),
  KEY `idx_open_application_scope_app` (`application_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='开放平台应用授权范围';

CREATE TABLE `open_user_authorization`
(
  `id`             bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `application_id` bigint unsigned NOT NULL COMMENT '所属应用 ID',
  `user_id`        bigint unsigned NOT NULL COMMENT '授权用户 ID',
  `open_id`        varchar(64) NOT NULL COMMENT '对应用暴露的用户标识（由 userId 派生，im_ 前缀）',
  `scope`          varchar(512) NOT NULL COMMENT '已授权范围（空格分隔）',
  `status`         varchar(32) NOT NULL COMMENT '授权状态：ACTIVE/REVOKED',
  `authorized_at`  datetime(3) NOT NULL COMMENT '授权时间',
  `revoked_at`     datetime(3) NULL COMMENT '撤销时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_open_user_authorization_app_user` (`application_id`, `user_id`),
  KEY `idx_open_user_authorization_open_id` (`open_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='开放平台用户授权';

-- 种子应用 saas-ktv：appSecret 仅存 SHA-256 哈希（原文 saas-ktv-secret），注册即 APPROVED。
INSERT INTO `open_application`
  (`app_id`, `app_name`, `app_type`, `callback_url`, `app_secret_hash`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('saas-ktv', 'SaaS KTV 业务', 'THIRD_PARTY', 'gvchat://oauth/callback',
   '4b444ccf84dc297e02fb3c5b3d32a22252143765c1bfdb0cb26a99963070f18a', 'APPROVED', 0, CURRENT_TIMESTAMP(3), 0, CURRENT_TIMESTAMP(3));

INSERT INTO `open_application_scope` (`application_id`, `scope`)
SELECT `id`, 'profile.basic' FROM `open_application` WHERE `app_id` = 'saas-ktv';

INSERT INTO `open_application_scope` (`application_id`, `scope`)
SELECT `id`, 'profile.phone' FROM `open_application` WHERE `app_id` = 'saas-ktv';

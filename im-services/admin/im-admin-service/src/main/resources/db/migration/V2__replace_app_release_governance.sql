-- 管理域客户端发布治理重构；依赖已执行的 V1 基线。
CREATE TABLE `adm_client_release`
(
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `platform` ENUM('android', 'ios', 'windows', 'macos', 'linux') NOT NULL COMMENT '运行操作系统平台',
  `channel` ENUM('internal', 'beta', 'stable') NOT NULL COMMENT '发行渠道',
  `version` varchar(32) NOT NULL COMMENT '展示版本号',
  `build_number` bigint unsigned NOT NULL COMMENT '平台渠道内递增构建号',
  `status` ENUM('draft', 'scheduled', 'rolling_out', 'released', 'paused', 'withdrawn', 'archived') NOT NULL COMMENT '发布状态',
  `mandatory` tinyint(1) NOT NULL DEFAULT 0 COMMENT '命中更新是否强制升级',
  `rollout_percent` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '灰度比例',
  `rollout_salt` varchar(64) NOT NULL COMMENT '稳定灰度哈希盐',
  `scheduled_at` datetime(3) NULL COMMENT '计划发布时间',
  `published_at` datetime(3) NULL COMMENT '首次对客户端可见时间',
  `release_notes` text NOT NULL COMMENT '发布说明',
  `store_url` varchar(1024) NULL COMMENT '受控商店更新地址',
  `compatibility_json` json NOT NULL COMMENT '已校验的协议兼容快照',
  `active_delivery_guard` varchar(24) GENERATED ALWAYS AS (IF(`status` IN ('scheduled', 'rolling_out'), 'active', NULL)) STORED COMMENT '用于保证同平台渠道仅一个活动发布',
  `row_version` bigint unsigned NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_client_release_platform_channel_build` (`platform`, `channel`, `build_number`),
  UNIQUE KEY `uk_adm_client_release_platform_channel_version` (`platform`, `channel`, `version`),
  UNIQUE KEY `uk_adm_client_release_active_delivery` (`platform`, `channel`, `active_delivery_guard`),
  KEY `idx_adm_client_release_lookup` (`platform`, `channel`, `status`, `build_number` DESC),
  KEY `idx_adm_client_release_schedule` (`status`, `scheduled_at`),
  CONSTRAINT `ck_adm_client_release_build_number` CHECK (`build_number` > 0),
  CONSTRAINT `ck_adm_client_release_rollout_percent` CHECK (`rollout_percent` <= 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域客户端发布聚合';

CREATE TABLE `adm_client_release_artifact`
(
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `release_id` bigint unsigned NOT NULL COMMENT '所属客户端发布 ID',
  `architecture` ENUM('universal', 'x64', 'arm64') NOT NULL COMMENT '目标架构',
  `package_type` ENUM('apk', 'google-play', 'app-store', 'msix', 'exe', 'dmg', 'pkg', 'flatpak', 'appimage') NOT NULL COMMENT '安装包类型',
  `download_url` varchar(1024) NULL COMMENT '受控 HTTPS 制品地址',
  `sha256` char(64) NULL COMMENT '直装制品 SHA-256 摘要',
  `size_bytes` bigint unsigned NULL COMMENT '直装制品字节数',
  `signing_metadata_json` json NULL COMMENT '签名或公证元数据',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm_client_release_artifact_target` (`release_id`, `architecture`, `package_type`),
  CONSTRAINT `ck_adm_client_release_artifact_size` CHECK (`size_bytes` IS NULL OR `size_bytes` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域客户端发布制品';

CREATE TABLE `adm_client_release_policy`
(
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `platform` ENUM('android', 'ios', 'windows', 'macos', 'linux') NOT NULL COMMENT '运行操作系统平台',
  `channel` ENUM('internal', 'beta', 'stable') NOT NULL COMMENT '发行渠道',
  `minimum_build_number` bigint unsigned NOT NULL COMMENT '最低受支持构建号',
  `row_version` bigint unsigned NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '最后更新时间',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_adm_client_release_policy_platform_channel` (`platform`, `channel`),
  CONSTRAINT `ck_adm_client_release_policy_minimum_build` CHECK (`minimum_build_number` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域客户端最低支持版本策略';

CREATE TABLE `adm_client_release_audit_log`
(
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键', `release_id` bigint unsigned NULL, `policy_id` bigint unsigned NULL,
  `action` ENUM('create', 'update', 'submit', 'rollout', 'pause', 'resume', 'withdraw', 'archive', 'upsert_policy', 'scheduled_publish') NOT NULL,
  `before_status` ENUM('draft', 'scheduled', 'rolling_out', 'released', 'paused', 'withdrawn', 'archived') NULL,
  `after_status` ENUM('draft', 'scheduled', 'rolling_out', 'released', 'paused', 'withdrawn', 'archived') NULL,
  `request_id` varchar(64) NOT NULL, `idempotency_key` char(36) NOT NULL, `reason` varchar(512) NOT NULL, `payload_digest` char(64) NOT NULL,
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL, `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_adm_client_release_audit_release_created` (`release_id`, `created_at` DESC),
  KEY `idx_adm_client_release_audit_policy_created` (`policy_id`, `created_at` DESC), UNIQUE KEY `uk_adm_client_release_audit_idempotency` (`idempotency_key`),
  CONSTRAINT `ck_adm_client_release_audit_subject` CHECK (`release_id` IS NOT NULL OR `policy_id` IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域客户端发布不可变审计日志';

CREATE TABLE `adm_client_release_operation`
(
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键', `idempotency_key` char(36) NOT NULL,
  `action` ENUM('create', 'update', 'submit', 'rollout', 'pause', 'resume', 'withdraw', 'archive', 'upsert_policy', 'scheduled_publish') NOT NULL,
  `request_digest` char(64) NOT NULL, `release_id` bigint unsigned NULL, `policy_id` bigint unsigned NULL,
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL, `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_adm_client_release_operation_idempotency` (`idempotency_key`),
  CONSTRAINT `ck_adm_client_release_operation_subject` CHECK (`release_id` IS NOT NULL OR `policy_id` IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理域客户端发布写操作幂等记录';

RENAME TABLE `adm_app_release` TO `adm_app_release_retired_v1`;

-- 资源与占用基线
CREATE TABLE `res_resource` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '资源ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `resource_type` varchar(32) NOT NULL COMMENT '资源类型 KTV_ROOM/KTV_SERVER（HOTEL_ROOM/SPA_ROOM/THERAPIST 后续）',
  `resource_code` varchar(64) NOT NULL COMMENT '资源编码',
  `name` varchar(128) NOT NULL COMMENT '资源名称',
  `parent_id` bigint unsigned NULL COMMENT '父资源',
  `capacity` int NULL COMMENT '容量',
  `status` varchar(24) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED/MAINTENANCE',
  `attributes_json` json NULL COMMENT '扩展属性',
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_res_resource_tenant_store_type_code` (`tenant_id`, `store_id`, `resource_type`, `resource_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='经营资源';

CREATE TABLE `res_schedule` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `tenant_id` bigint unsigned NOT NULL, `store_id` bigint unsigned NOT NULL,
  `resource_id` bigint unsigned NOT NULL, `start_at` datetime(3) NOT NULL, `end_at` datetime(3) NOT NULL,
  `schedule_type` varchar(32) NOT NULL, `status` varchar(24) NOT NULL DEFAULT 'ACTIVE', `source` varchar(32) NULL,
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_res_schedule_resource_time` (`tenant_id`, `resource_id`, `start_at`, `end_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资源排班';

CREATE TABLE `res_occupation` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `tenant_id` bigint unsigned NOT NULL, `store_id` bigint unsigned NOT NULL,
  `resource_id` bigint unsigned NOT NULL, `source_type` varchar(32) NOT NULL COMMENT '来源类型 ORDER/RESERVATION',
  `source_id` bigint unsigned NOT NULL COMMENT '来源ID',
  `start_at` datetime(3) NOT NULL, `end_at` datetime(3) NOT NULL,
  `status` varchar(24) NOT NULL DEFAULT 'HELD' COMMENT 'HELD/RESERVED/IN_USE/RELEASED/CANCELLED',
  `hold_expires_at` datetime(3) NULL COMMENT 'HELD 过期时间', `version` int NOT NULL DEFAULT 0,
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_res_occupation_resource_time_status` (`tenant_id`, `resource_id`, `start_at`, `end_at`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资源占用';

-- 三层工作区：门店默认独立工作台，租户可按客户规模切换为租户后台内的门店上下文。
CREATE TABLE IF NOT EXISTS `tnt_workspace_config` (
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_workspace_mode` varchar(24) NOT NULL DEFAULT 'INDEPENDENT' COMMENT 'INDEPENDENT/TENANT_CONTEXT',
  `created_by` bigint unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工作区配置';

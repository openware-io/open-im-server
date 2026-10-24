-- S4 租户应用安装：应用目录仍在 IM open_application，这里只记录租户安装事实。
CREATE TABLE `tnt_app_installation` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `app_id` varchar(64) NOT NULL COMMENT '应用ID，对应 IM open_application.app_id',
  `client_type` varchar(8) NOT NULL COMMENT '客户端族 C/B',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/SUSPENDED/UNINSTALLED',
  `installed_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '安装人账号ID',
  `installed_at` datetime(3) NOT NULL COMMENT '安装时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_app_installation_tenant_app` (`tenant_id`, `app_id`),
  KEY `idx_tnt_app_installation_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户应用安装';

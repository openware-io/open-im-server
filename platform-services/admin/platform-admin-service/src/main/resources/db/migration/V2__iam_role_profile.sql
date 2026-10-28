-- 角色工作台档案：角色决定工作区、登录落点和数据范围。
-- 与 iam_role 通过 role_code 关联（角色表归 tenant 域，本表只存工作台投影，避免跨域写表）。
CREATE TABLE IF NOT EXISTS `iam_role_profile` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role_code` varchar(64) NOT NULL COMMENT '角色码，对应 iam_role.code',
  `workspace_code` varchar(32) NOT NULL COMMENT '工作区 PLATFORM/HEADQUARTERS/STORE/IM',
  `landing_route` varchar(255) NOT NULL COMMENT '登录落点，支持 {storeId} 占位',
  `default_scope` varchar(32) NOT NULL COMMENT '默认数据范围 PLATFORM/TENANT/STORE/IM',
  `default_business_type` varchar(32) NULL COMMENT '默认业态，可空',
  `nav_profile` varchar(64) NOT NULL DEFAULT 'default' COMMENT '导航档案',
  `sort_no` int NOT NULL DEFAULT 100 COMMENT '同角色多工作区时的优先级，越小越优先',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
  `created_by` bigint unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_role_profile_role_workspace` (`role_code`, `workspace_code`),
  KEY `idx_iam_role_profile_role` (`role_code`),
  KEY `idx_iam_role_profile_workspace` (`workspace_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色工作台档案';

INSERT INTO `iam_role_profile`
  (`role_code`, `workspace_code`, `landing_route`, `default_scope`, `default_business_type`, `nav_profile`, `sort_no`, `status`, `created_at`, `updated_at`)
VALUES
  ('platform.operator', 'PLATFORM', '/admin/platform/tenants', 'PLATFORM', NULL, 'default', 10, 'ACTIVE', NOW(3), NOW(3)),
  ('tenant.owner', 'HEADQUARTERS', '/admin/tenant/overview', 'TENANT', NULL, 'default', 20, 'ACTIVE', NOW(3), NOW(3)),
  ('store.manager', 'STORE', '/admin/tenant/overview', 'STORE', NULL, 'default', 30, 'ACTIVE', NOW(3), NOW(3)),
  ('store.cashier', 'STORE', '/business/orders', 'STORE', NULL, 'default', 40, 'ACTIVE', NOW(3), NOW(3)),
  ('store.finance', 'STORE', '/admin/reports', 'STORE', NULL, 'default', 50, 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE
  `workspace_code` = VALUES(`workspace_code`),
  `landing_route` = VALUES(`landing_route`),
  `default_scope` = VALUES(`default_scope`),
  `nav_profile` = VALUES(`nav_profile`),
  `sort_no` = VALUES(`sort_no`),
  `status` = VALUES(`status`),
  `updated_at` = VALUES(`updated_at`);

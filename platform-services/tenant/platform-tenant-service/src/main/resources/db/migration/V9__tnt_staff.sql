-- Domain: tenant/IAM; incremental migration.
CREATE TABLE `tnt_staff` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '员工档案ID',
  `membership_id` bigint unsigned NOT NULL COMMENT '成员身份ID（tnt_membership.id）',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `employee_no` varchar(64) NOT NULL COMMENT '员工编号',
  `display_name_cipher` varchar(255) NOT NULL COMMENT '员工姓名密文（PII，AES-256-GCM）',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/LEFT',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_staff_membership` (`membership_id`),
  UNIQUE KEY `uk_tnt_staff_tenant_employee_no` (`tenant_id`, `employee_no`),
  KEY `idx_tnt_staff_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户员工档案';

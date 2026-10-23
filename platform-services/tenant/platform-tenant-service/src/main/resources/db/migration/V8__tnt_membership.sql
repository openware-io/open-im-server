-- Domain: tenant/IAM; applies only to a new database without executed baseline.
CREATE TABLE `tnt_membership` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '成员身份ID',
  `account_id` bigint unsigned NOT NULL COMMENT 'SaaS账号ID（idt_account.id，仅标识）',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `organization_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '组织ID；0表示未指定',
  `membership_type` varchar(16) NOT NULL COMMENT '成员类型 STAFF/CUSTOMER',
  `member_no` varchar(64) NOT NULL COMMENT '租户内成员编号',
  `authorization_version` int NOT NULL DEFAULT 1 COMMENT '授权版本；角色/权限变更递增',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/SUSPENDED/LEFT',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_membership_account_tenant_type` (`account_id`, `tenant_id`, `membership_type`),
  UNIQUE KEY `uk_tnt_membership_tenant_no` (`tenant_id`, `member_no`),
  KEY `idx_tnt_membership_tenant_type_status` (`tenant_id`, `membership_type`, `status`),
  KEY `idx_tnt_membership_tenant_org` (`tenant_id`, `organization_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户成员身份';

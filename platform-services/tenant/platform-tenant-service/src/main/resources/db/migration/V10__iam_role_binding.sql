-- Domain: tenant/IAM; incremental migration.
CREATE TABLE `iam_role_binding` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '角色绑定ID',
  `subject_type` varchar(16) NOT NULL COMMENT '授权主体类型 ACCOUNT/MEMBERSHIP',
  `subject_id` bigint unsigned NOT NULL COMMENT '主体ID',
  `role_id` bigint unsigned NOT NULL COMMENT '角色ID（iam_role.id）',
  `scope_type` varchar(16) NOT NULL COMMENT '作用域 PLATFORM/TENANT/ORGANIZATION/STORE/SELF',
  `tenant_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '租户ID；平台为0',
  `organization_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '组织ID；非组织为0',
  `store_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '门店ID；非门店为0',
  `authorization_version` int NOT NULL DEFAULT 1 COMMENT '绑定授权版本；变更递增',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/REVOKED',
  `effective_from` datetime(3) NULL COMMENT '生效时间',
  `effective_to` datetime(3) NULL COMMENT '失效时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_role_binding_subject_role_scope`
    (`subject_type`, `subject_id`, `role_id`, `scope_type`, `tenant_id`, `organization_id`, `store_id`),
  KEY `idx_iam_role_binding_tenant_scope` (`tenant_id`, `scope_type`, `status`),
  KEY `idx_iam_role_binding_subject` (`subject_type`, `subject_id`, `status`),
  KEY `idx_iam_role_binding_org_scope` (`tenant_id`, `organization_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色绑定';

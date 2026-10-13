-- =====================================================================================
-- platform-tenant-service 迁移基线（合并重建）
-- =====================================================================================
-- 本文件是原 V1..V32 共 32 个迁移文件的合并基线，单独执行即可得到最终结构与最终数据状态。
-- 数据库按 PR-01/PR-03 销毁重建，因此不保留任何历史 ALTER TABLE 兼容性。
--
-- 合并约定：
--   1) 表结构一律取「全部 32 个文件执行完毕后的最终形状」：原分散在多个文件中的
--      ALTER TABLE ... ADD COLUMN / MODIFY COLUMN / DROP KEY 全部折叠回 CREATE TABLE；
--   2) 所有表名、列名、索引名一律使用反引号（DATABASE_MIGRATION_STANDARD.md）；
--   3) 全表强制四审计列 created_by / updated_by / created_at / updated_at（DC-11）；
--   4) 主键统一 `id` bigint unsigned NOT NULL AUTO_INCREMENT，原业务键/复合键改为 UNIQUE KEY；
--   5) 索引名统一 uk_<完整表名>_<语义> / idx_<完整表名>_<语义>；
--   6) 表尾统一 ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci + 表级 COMMENT；
--   7) 种子 INSERT 全部保留并保持幂等（ON DUPLICATE KEY UPDATE / WHERE NOT EXISTS）；
--      真正的存量数据回填语句保留在本文件末尾。
--
-- 表命名例外（不重命名）：`iam_approval`。
--   该表前缀 `iam_` 虽未登记，但属于挂起的架构决策（见 ENGINEERING_GOVERNANCE_02_AUDIT.md），
--   本轮保持表名不变，仅补齐缺失的反引号与四审计列。
--
-- 说明：本模块不拥有 `tenant_payment_method`（属 common-payment-service，见其
--   V9__tenant_payment_method.sql）。本模块仅在原 V12 的一行注释中提及该表，
--   故此处不做 `tnt_payment_method` 改名。
-- =====================================================================================


-- =====================================================================================
-- 第 1 部分：租户域表结构（tnt_）
-- =====================================================================================

-- 租户
CREATE TABLE `tnt_tenant` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '租户ID',
  `tenant_code` varchar(32) NOT NULL COMMENT '租户编码',
  `name` varchar(128) NOT NULL COMMENT '租户名称',
  `status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT '状态 PENDING/ACTIVE/SUSPENDED/CLOSED',
  `default_locale` varchar(16) NULL COMMENT '默认语言',
  `default_timezone` varchar(64) NULL COMMENT '默认IANA时区',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人账号ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人账号ID',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_tenant_code` (`tenant_code`),
  KEY `idx_tnt_tenant_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户';


-- 组织
CREATE TABLE `tnt_organization` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '组织ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `code` varchar(64) NOT NULL COMMENT '组织编码',
  `name` varchar(128) NOT NULL COMMENT '组织名称',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/SUSPENDED/CLOSED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人账号ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人账号ID',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_organization_tenant_code` (`tenant_id`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='组织';


-- 门店（timezone / business_day_cutoff 的 NOT NULL + DEFAULT 为原 V27 折叠结果）
CREATE TABLE `tnt_store` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '门店ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `organization_id` bigint unsigned NOT NULL COMMENT '组织ID',
  `code` varchar(64) NOT NULL COMMENT '门店编码',
  `name` varchar(128) NOT NULL COMMENT '门店名称',
  `business_type` varchar(32) NOT NULL COMMENT '业态 KTV/HOTEL/SPA/MASSAGE/RETAIL',
  `country_code` char(2) NULL COMMENT '国家代码 ISO 3166-1 alpha-2',
  `region_code` varchar(32) NULL COMMENT '行政区划代码',
  `timezone` varchar(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT 'IANA时区（门店级权威来源）',
  `default_currency` char(3) NULL COMMENT '默认币种 ISO 4217',
  `locale` varchar(16) NULL COMMENT '默认语言',
  `tax_profile_id` bigint unsigned NULL COMMENT '税务方案ID',
  `business_day_cutoff` time NOT NULL DEFAULT '04:00:00' COMMENT '营业日切点（00:00-12:00，默认04:00）',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/SUSPENDED/CLOSED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人账号ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人账号ID',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_store_tenant_code` (`tenant_id`, `code`),
  KEY `idx_tnt_store_org_status` (`tenant_id`, `organization_id`, `status`),
  KEY `idx_tnt_store_business_status` (`tenant_id`, `business_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='门店';


-- 租户配置（store_id 维度为原 V4 折叠结果，business_type 维度与最终唯一键为原 V31 折叠结果）
CREATE TABLE `tnt_tenant_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '配置ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '门店ID，0=租户级默认（作用于所有门店），>0=门店级覆盖',
  `business_type` varchar(32) NOT NULL DEFAULT '' COMMENT '业态编码，空字符串=租户默认',
  `config_key` varchar(64) NOT NULL COMMENT '配置键 如 wallet_brand_name',
  `config_value` varchar(255) NOT NULL COMMENT '配置值',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/DISABLED',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人账号ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人账号ID',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_tenant_config_scope_key` (`tenant_id`, `business_type`, `store_id`, `config_key`),
  KEY `idx_tnt_tenant_config_resolution` (`tenant_id`, `config_key`, `business_type`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户配置';


-- 业态枚举
CREATE TABLE `tnt_business_type` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '业态ID',
  `code` varchar(32) NOT NULL COMMENT '业态编码',
  `name` varchar(64) NOT NULL COMMENT '业态名称',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/DISABLED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人账号ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人账号ID',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_business_type_code` (`code`),
  KEY `idx_tnt_business_type_status_code` (`status`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业态枚举';

-- 商户主体（法人实体）：资金/储值归属的法人公司
-- 口径依据：KTV_BUSINESS_01_SERVICE.md §8.1 / SAAS_PLATFORM_04_DATA.md（cst_wallet_account.legal_entity_id 引用本表）
CREATE TABLE `tnt_legal_entity` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '法人主体ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `name` varchar(128) NOT NULL COMMENT '法人主体名称',
  `unified_social_credit_code` varchar(32) NULL COMMENT '统一社会信用代码',
  `currency_code` char(3) NULL DEFAULT 'CNY' COMMENT '结算币种',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/SUSPENDED/CLOSED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人账号ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人账号ID',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_legal_entity_tenant_code` (`tenant_id`, `unified_social_credit_code`),
  KEY `idx_tnt_legal_entity_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商户主体(法人实体)';


-- 门店计价方案（KTV 首发）
-- business_type / version / idempotency_key 三者及最终唯一键为原 V32 折叠结果
CREATE TABLE `tnt_pricing_plan` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '计价方案ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `business_type` varchar(32) NOT NULL DEFAULT '' COMMENT '业态编码，空字符串=租户默认',
  `resource_type` varchar(32) NOT NULL COMMENT '资源类型 KTV_ROOM/KTV_SERVER',
  `billing_unit` varchar(16) NOT NULL DEFAULT 'HOUR' COMMENT '计费单位 HOUR/HALF_HOUR/PACKAGE',
  `increment_minutes` int NOT NULL DEFAULT 30 COMMENT '递增粒度（分钟）',
  `rounding_direction` varchar(24) NOT NULL DEFAULT 'CONSUMER_FAVOR' COMMENT '舍入方向 CONSUMER_FAVOR/ROUND_UP/FLOOR_BLOCK',
  `price_per_unit` bigint NOT NULL COMMENT '每单位单价（最小货币单位）',
  `default_session_minutes` int NOT NULL DEFAULT 120 COMMENT '标准时长（无预订快速开台判定超时）',
  `overtime_rate` decimal(20,6) NOT NULL DEFAULT 1.0 COMMENT '超时费率',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/DISABLED',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `idempotency_key` varchar(96) DEFAULT NULL COMMENT '写入幂等键',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人账号ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人账号ID',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_pricing_plan_scope_type` (`tenant_id`, `business_type`, `store_id`, `resource_type`),
  UNIQUE KEY `uk_tnt_pricing_plan_idempotency` (`tenant_id`, `idempotency_key`, `resource_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='门店计价方案';


-- 租户配置登记表：只描述归属与合并策略，不提供任意 JSON 代写能力。
-- business_type='' 表示租户默认；store_id=0 + business_type 非空表示业态默认；store_id>0 表示门店覆盖。
CREATE TABLE `tnt_config_definition` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '配置登记ID',
  `config_key` varchar(64) NOT NULL COMMENT '配置键',
  `owner_service` varchar(64) NOT NULL COMMENT '归属服务',
  `value_type` varchar(24) NOT NULL COMMENT '值类型 STRING/INTEGER/TIME_RANGE/CURRENCY/DECIMAL_RULE/DURATION',
  `scope_policy` varchar(32) NOT NULL COMMENT '作用域策略 TENANT/BUSINESS/STORE',
  `merge_strategy` varchar(24) NOT NULL DEFAULT 'OVERRIDE' COMMENT '合并策略 OVERRIDE',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/DISABLED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人账号ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人账号ID',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_config_definition_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户配置登记表';


-- 应用用户授权：企业安装授权的用户级授权
CREATE TABLE `tnt_app_user_authorization` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `app_id` varchar(128) NOT NULL COMMENT '应用标识',
  `account_id` bigint unsigned NOT NULL COMMENT 'SaaS账号标识',
  `tenant_id` bigint unsigned NOT NULL COMMENT '企业租户标识',
  `scope` varchar(1024) NOT NULL COMMENT '用户批准的API范围',
  `status` varchar(32) NOT NULL COMMENT '状态 ACTIVE or REVOKED',
  `authorization_version` int NOT NULL DEFAULT '1' COMMENT '授权版本',
  `authorized_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '授权时间',
  `revoked_at` datetime(3) NULL COMMENT '撤销时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人账号ID',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人账号ID',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_app_user_authorization_app_account` (`app_id`, `account_id`),
  KEY `idx_tnt_app_user_authorization_tenant` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应用用户授权';

-- =====================================================================================
-- 第 2 部分：IAM 表结构（iam_）
-- =====================================================================================

-- 权限（scope_level / domain_code / grantable_levels / menu_code 为原 V28 折叠结果；
--   三列 NOT NULL，保留原 V28 的列 DEFAULT 作为早期播种的兜底；
--   全表取值由 3.8 的「权限作用域元数据」语句（按其原 V28 位置）统一给出，
--   V30 显式登记的行随后覆盖）
CREATE TABLE `iam_permission` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '权限ID',
  `code` varchar(128) NOT NULL COMMENT '权限码 module.resource.action',
  `module` varchar(64) NOT NULL COMMENT '模块',
  `resource` varchar(64) NOT NULL COMMENT '资源',
  `action` varchar(64) NOT NULL COMMENT '动作',
  `description` varchar(255) NULL COMMENT '权限描述',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/DISABLED',
  `scope_level` varchar(16) NOT NULL DEFAULT 'TENANT' COMMENT '权限适用作用域 PLATFORM/TENANT/STORE',
  `domain_code` varchar(64) NOT NULL DEFAULT 'core' COMMENT '权限所属业务域',
  `grantable_levels` varchar(64) NOT NULL DEFAULT 'TENANT,STORE' COMMENT '允许授予的目标作用域，逗号分隔',
  `menu_code` varchar(128) NULL COMMENT '关联菜单 code',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人账号ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人账号ID',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_permission_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权限';


-- 角色（scope_level / domain_code 为原 V28 折叠结果，可空）
CREATE TABLE `iam_role` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `tenant_id` bigint unsigned NULL COMMENT '平台角色为空',
  `code` varchar(64) NOT NULL COMMENT '角色编码',
  `name` varchar(128) NOT NULL COMMENT '角色名称',
  `role_type` varchar(16) NOT NULL COMMENT 'sdk/PRESET/TENANT',
  `copy_from_role_id` bigint unsigned NULL COMMENT '复制来源角色ID',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/DISABLED',
  `scope_level` varchar(16) NULL COMMENT '角色作用域 PLATFORM/TENANT/STORE',
  `domain_code` varchar(64) NULL COMMENT '角色所属业务域',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人账号ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人账号ID',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_role_tenant_code` (`tenant_id`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色';


-- 角色权限
-- 【主键转换】原为复合主键 (role_id, permission_id)；按基线规范改为 `id` 单列自增主键，
-- 原复合键降级为唯一键 `uk_iam_role_permission_role_permission`（保持原「同一角色同一权限只一行」约束）。
CREATE TABLE `iam_role_permission` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role_id` bigint unsigned NOT NULL COMMENT '角色ID',
  `permission_id` bigint unsigned NOT NULL COMMENT '权限ID',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人账号ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人账号ID',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_role_permission_role_permission` (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色权限';


-- 用户角色分配
CREATE TABLE `iam_user_role` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `account_id` bigint unsigned NOT NULL COMMENT '账号ID',
  `tenant_id` bigint unsigned NULL COMMENT '租户ID（平台级作用域为空，原 V10 的 MODIFY 折叠结果）',
  `organization_id` bigint unsigned NULL COMMENT '组织ID',
  `store_id` bigint unsigned NULL COMMENT '门店ID',
  `role_id` bigint unsigned NOT NULL COMMENT '角色ID',
  `scope_type` varchar(16) NOT NULL COMMENT '作用域类型 TENANT/ORGANIZATION/STORE/SELF/PLATFORM',
  `effective_from` datetime(3) NULL COMMENT '生效时间',
  `effective_to` datetime(3) NULL COMMENT '失效时间',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/DISABLED',
  `authorization_version` int NOT NULL DEFAULT 0 COMMENT '授权版本',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人账号ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人账号ID',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_iam_user_role_account_status` (`account_id`, `status`),
  KEY `idx_iam_user_role_scope` (`tenant_id`, `organization_id`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色分配';


-- 消费端应用（企业安装授权的应用级授权）
-- 【主键转换】原主键为业务键 app_id；按基线规范改为 `id` 单列自增主键，
-- app_id 降级为唯一键 `uk_iam_consumer_application_app_id`。
CREATE TABLE `iam_consumer_application` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `app_id` varchar(64) NOT NULL COMMENT '应用标识',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `organization_id` bigint unsigned NULL COMMENT '组织ID',
  `store_id` bigint unsigned NULL COMMENT '门店ID',
  `permissions_json` varchar(2000) NOT NULL COMMENT '应用获批权限快照(JSON)',
  `authorization_version` int NOT NULL DEFAULT 1 COMMENT '授权版本',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/REVOKED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人账号ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人账号ID',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_consumer_application_app_id` (`app_id`),
  KEY `idx_iam_consumer_application_scope` (`tenant_id`, `organization_id`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消费端应用';


-- IAM 高风险动作在线复核（二次复核）
-- 角色/权限变更、退款审批、密钥操作等高风险动作先落 PENDING 复核，复核通过后执行后续动作。
-- 【命名例外】表名保持 `iam_approval` 不变：`iam_` 前缀未登记属挂起的架构决策，本轮不重命名，
-- 仅补齐原文件缺失的反引号（表名与全部列名）与四审计列。
CREATE TABLE `iam_approval` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `action_type` varchar(64) NOT NULL COMMENT '高风险动作类型 ROLE_ASSIGN/ROLE_PERMISSION_ASSIGN/REFUND_APPROVAL/KEY_OPERATION',
  `resource_type` varchar(64) NOT NULL COMMENT '资源类型 role/user_role/refund/api_key',
  `resource_id` varchar(128) NOT NULL COMMENT '资源标识',
  `operator_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '发起人账号ID',
  `detail_json` text NULL COMMENT '操作负载快照(JSON)',
  `status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT '状态 PENDING/APPROVED/REJECTED',
  `idempotency_key` varchar(128) NULL COMMENT '幂等键',
  `approver_id` bigint unsigned NULL COMMENT '复核人账号ID',
  `review_comment` varchar(512) NULL COMMENT '复核意见',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人账号ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人账号ID',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  `reviewed_at` datetime(3) NULL COMMENT '复核时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_approval_idempotency` (`tenant_id`, `idempotency_key`),
  KEY `idx_iam_approval_tenant_status` (`tenant_id`, `status`),
  KEY `idx_iam_approval_tenant_action` (`tenant_id`, `action_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IAM 高风险动作在线复核';

-- =====================================================================================
-- 第 3 部分：种子数据
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 3.1 默认租户（default）+ 首组织（headquarter）+ 首门店（store-001, KTV）
--     幂等：依赖唯一键重复跳过
-- -------------------------------------------------------------------------------------
INSERT INTO `tnt_tenant`
  (`tenant_code`, `name`, `status`, `default_locale`, `default_timezone`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('default', '默认租户', 'ACTIVE', 'zh-CN', 'Asia/Shanghai', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `updated_at` = `tnt_tenant`.`updated_at`;

-- -------------------------------------------------------------------------------------
-- 3.2 A380 租户初始化（测试种子，后续测试可人工调整）
--     固定 ID 便于跨服务引用：tenant/org/store/legal_entity/pricing_plan = 100，门店资源 = 1001+
-- -------------------------------------------------------------------------------------
INSERT INTO `tnt_tenant`
  (`id`, `tenant_code`, `name`, `status`, `default_locale`, `default_timezone`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  (100, 'a380', 'A380', 'ACTIVE', 'zh-CN', 'Asia/Shanghai', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `updated_at` = `tnt_tenant`.`updated_at`;

INSERT INTO `tnt_organization`
  (`tenant_id`, `code`, `name`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT `id`, 'headquarter', '总部', 'ACTIVE', 0, NOW(3), 0, NOW(3)
FROM `tnt_tenant` WHERE `tenant_code` = 'default'
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `updated_at` = `tnt_organization`.`updated_at`;

INSERT INTO `tnt_organization`
  (`id`, `tenant_id`, `code`, `name`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  (100, 100, 'a380-hq', 'A380 总部', 'ACTIVE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `updated_at` = `tnt_organization`.`updated_at`;

INSERT INTO `tnt_store`
  (`tenant_id`, `organization_id`, `code`, `name`, `business_type`, `country_code`, `timezone`, `default_currency`, `locale`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT `t`.`id`, `o`.`id`, 'store-001', '首店', 'KTV', 'CN', 'Asia/Shanghai', 'CNY', 'zh-CN', 'ACTIVE', 0, NOW(3), 0, NOW(3)
FROM `tnt_tenant` `t` JOIN `tnt_organization` `o` ON `o`.`tenant_id` = `t`.`id` AND `o`.`code` = 'headquarter'
WHERE `t`.`tenant_code` = 'default'
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `updated_at` = `tnt_store`.`updated_at`;

INSERT INTO `tnt_store`
  (`id`, `tenant_id`, `organization_id`, `code`, `name`, `business_type`, `country_code`, `timezone`, `default_currency`, `locale`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  (100, 100, 100, 'a380-ktv-001', 'A380 KTV 旗舰店', 'KTV', 'CN', 'Asia/Shanghai', 'CNY', 'zh-CN', 'ACTIVE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `updated_at` = `tnt_store`.`updated_at`;

-- -------------------------------------------------------------------------------------
-- 3.3 业态字典
-- -------------------------------------------------------------------------------------
INSERT INTO `tnt_business_type`
  (`code`, `name`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('KTV', 'KTV', 'ACTIVE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `updated_at` = `tnt_business_type`.`updated_at`;

-- -------------------------------------------------------------------------------------
-- 3.4 商户主体（法人实体）
-- -------------------------------------------------------------------------------------
INSERT INTO `tnt_legal_entity`
  (`id`, `tenant_id`, `name`, `unified_social_credit_code`, `currency_code`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  (100, 100, '深圳市A380娱乐有限公司', '91440300MA5A380001', 'CNY', 'ACTIVE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `updated_at` = `tnt_legal_entity`.`updated_at`;

-- -------------------------------------------------------------------------------------
-- 3.5 租户配置
--   A380：储值品牌名 + 默认法人主体（原 V7）
--   默认租户 Feature Flag 灰度配置占位（原 V4）：
--     1) tenant 维度：tenant_id 定位租户，store_id = 0 表示「租户级默认开关」，作用于该租户下所有门店。
--     2) store  维度：store_id > 0 表示「门店级覆盖」，仅对该门店生效（可做单店灰度放量）。
--     3) 读取优先级：门店覆盖 > 业态默认 > 租户默认 > 全局硬编码默认（由领域应用服务统一实现）。
--     4) config_value 语义约定：'true'/'false'（布尔开关），后续可扩展 'percent:50'（按比例灰度）等取值。
-- -------------------------------------------------------------------------------------
INSERT INTO `tnt_tenant_config`
  (`tenant_id`, `store_id`, `business_type`, `config_key`, `config_value`, `status`, `version`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  (100, 0, '', 'wallet_brand_name', 'A380币', 'ACTIVE', 0, 0, NOW(3), 0, NOW(3)),
  (100, 0, '', 'default_legal_entity_id', '100', 'ACTIVE', 0, 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE `config_value` = VALUES(`config_value`), `updated_at` = `tnt_tenant_config`.`updated_at`;

-- 灰度起点：KTV 首发业态默认开启；线上支付（支付宝/微信/Stripe）默认关闭，与 SAAS_PLATFORM/KTV 方案一致。
INSERT INTO `tnt_tenant_config`
  (`tenant_id`, `store_id`, `business_type`, `config_key`, `config_value`, `status`, `version`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT `t`.`id`, 0, '', 'feature.ktv.enabled', 'true', 'ACTIVE', 0, 0, NOW(3), 0, NOW(3)
FROM `tnt_tenant` `t` WHERE `t`.`tenant_code` = 'default'
ON DUPLICATE KEY UPDATE `config_value` = VALUES(`config_value`), `updated_at` = `tnt_tenant_config`.`updated_at`;

INSERT INTO `tnt_tenant_config`
  (`tenant_id`, `store_id`, `business_type`, `config_key`, `config_value`, `status`, `version`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT `t`.`id`, 0, '', 'feature.online-payment.enabled', 'false', 'ACTIVE', 0, 0, NOW(3), 0, NOW(3)
FROM `tnt_tenant` `t` WHERE `t`.`tenant_code` = 'default'
ON DUPLICATE KEY UPDATE `config_value` = VALUES(`config_value`), `updated_at` = `tnt_tenant_config`.`updated_at`;

-- -------------------------------------------------------------------------------------
-- 3.6 配置登记（归属与合并策略）
-- -------------------------------------------------------------------------------------
INSERT INTO `tnt_config_definition`
  (`config_key`, `owner_service`, `value_type`, `scope_policy`, `merge_strategy`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('ktv_business_hours', 'platform-tenant-service', 'TIME_RANGE', 'TENANT/BUSINESS/STORE', 'OVERRIDE', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('currency', 'platform-tenant-service', 'CURRENCY', 'TENANT', 'OVERRIDE', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('wallet_brand_name', 'platform-tenant-service', 'STRING', 'TENANT', 'OVERRIDE', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('wallet_ratio', 'platform-tenant-service', 'INTEGER', 'TENANT', 'OVERRIDE', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('points_earn_rule', 'platform-customer-service', 'DECIMAL_RULE', 'TENANT/BUSINESS/STORE', 'OVERRIDE', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('points_redeem_rule', 'platform-customer-service', 'DECIMAL_RULE', 'TENANT/BUSINESS/STORE', 'OVERRIDE', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('points_expiry_days', 'platform-customer-service', 'INTEGER', 'TENANT/BUSINESS', 'OVERRIDE', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('points_redeem_cap', 'platform-customer-service', 'DECIMAL_RULE', 'BUSINESS/STORE', 'OVERRIDE', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('reservation_advance_window', 'platform-order-service', 'DURATION', 'TENANT/BUSINESS/STORE', 'OVERRIDE', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('reservation_cancel_window', 'platform-order-service', 'DURATION', 'TENANT/BUSINESS/STORE', 'OVERRIDE', 'ACTIVE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE
  `owner_service` = VALUES(`owner_service`),
  `value_type` = VALUES(`value_type`),
  `scope_policy` = VALUES(`scope_policy`),
  `merge_strategy` = VALUES(`merge_strategy`),
  `updated_at` = `tnt_config_definition`.`updated_at`;

-- -------------------------------------------------------------------------------------
-- 3.7 KTV 计价方案（按门店/资源类型唯一）：¥128/小时，标准 120 分钟，超时 1.0x
-- -------------------------------------------------------------------------------------
INSERT INTO `tnt_pricing_plan`
  (`id`, `tenant_id`, `store_id`, `business_type`, `resource_type`, `billing_unit`, `increment_minutes`, `rounding_direction`, `price_per_unit`, `default_session_minutes`, `overtime_rate`, `status`, `version`, `idempotency_key`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  (100, 100, 100, '', 'KTV_ROOM', 'HOUR', 30, 'CONSUMER_FAVOR', 12800, 120, 1.0, 'ACTIVE', 0, NULL, 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE `price_per_unit` = VALUES(`price_per_unit`), `updated_at` = `tnt_pricing_plan`.`updated_at`;

-- -------------------------------------------------------------------------------------
-- 3.8 IAM 权限码
--   基础集合（原 V3 + V9 + V11 + V12）：code 唯一，幂等。
--   scope_level / domain_code / grantable_levels 为 NOT NULL，取值由本节末尾的
--   「权限作用域元数据」语句（= 原 V28 的执行位置）与 V30 的显式登记共同给出。
-- -------------------------------------------------------------------------------------
INSERT INTO `iam_permission`
  (`code`, `module`, `resource`, `action`, `description`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('tenant.tenant.manage', 'tenant', 'tenant', 'manage', '租户管理', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('tenant.store.manage', 'tenant', 'store', 'manage', '门店管理', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('iam.role.manage', 'iam', 'role', 'manage', '角色管理', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('ktv.session.operate', 'ktv', 'session', 'operate', 'KTV 开台/结台', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('ktv.session.correct_pause', 'ktv', 'session', 'correct_pause', 'KTV 暂停修正', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('ktv.server.order', 'ktv', 'server', 'order', '服务人员点单', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('order.settle', 'order', 'order', 'settle', '订单结算', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('order.hold', 'order', 'order', 'hold', '订单挂单/解挂', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('order.transfer', 'order', 'order', 'transfer', '订单转台', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('order.void', 'order', 'order', 'void', '订单作废', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('payment.collect', 'payment', 'collect', 'operate', '组合收款', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('payment.refund.approve', 'payment', 'refund', 'approve', '退款审批', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('payment.refund.offline', 'payment', 'refund', 'offline', '线下退款', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('member.pii.view', 'member', 'pii', 'view', '查看会员隐私信息(姓名/手机号明文)', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('payment.method.cash', 'payment', 'method', 'cash', '现金支付', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('payment.method.wallet', 'payment', 'method', 'wallet', '储值币(A380币)支付', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('payment.method.point', 'payment', 'method', 'point', '积分抵扣', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('payment.method.alipay', 'payment', 'method', 'alipay', '支付宝', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('payment.method.wechat', 'payment', 'method', 'wechat', '微信支付', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('payment.method.stripe', 'payment', 'method', 'stripe', 'Stripe', 'ACTIVE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE
  `module` = VALUES(`module`), `resource` = VALUES(`resource`), `action` = VALUES(`action`),
  `updated_at` = `iam_permission`.`updated_at`;

-- 权限作用域元数据（原 V28 的无 WHERE 全表 UPDATE，逐字保留其口径与执行位置：
--   它必须早于 V25/V26/V30 新增权限的 INSERT，否则会覆盖 V30 显式登记的 scope_level / grantable_levels）
--   口径：platform.* 权限为 PLATFORM 作用域且只能授予 PLATFORM；
--         tenant.tenant.manage / tenant.store.manage / iam.role.manage 只能授予 TENANT；
--         其余为 TENANT/STORE。ktv.* 归 ktv 业务域，其余归 core。
UPDATE `iam_permission`
SET `scope_level` = CASE WHEN `code` LIKE 'platform.%' THEN 'PLATFORM' ELSE 'TENANT' END,
    `domain_code` = CASE WHEN `code` LIKE 'ktv.%' THEN 'ktv' ELSE 'core' END,
    `grantable_levels` = CASE
      WHEN `code` LIKE 'platform.%' THEN 'PLATFORM'
      WHEN `code` IN ('tenant.tenant.manage', 'tenant.store.manage', 'iam.role.manage') THEN 'TENANT'
      ELSE 'TENANT,STORE'
    END,
    `updated_at` = NOW(3);

-- 预约履约 / 订单加项 + KTV 业务闭环（原 V17 + V19 + V22）
INSERT INTO `iam_permission`
  (`code`, `module`, `resource`, `action`, `description`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('reservation.view', 'reservation', 'reservation', 'view', '查看预约', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('reservation.confirm', 'reservation', 'reservation', 'confirm', '确认预约', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('reservation.arrival', 'reservation', 'reservation', 'arrival', '预约到店', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('order.add_item', 'order', 'order', 'add_item', '订单加项', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('ktv.session.open', 'ktv', 'session', 'open', '开台/开单', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('ktv.server.end', 'ktv', 'server', 'end', '结束服务', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('order.view', 'order', 'order', 'view', '查看订单与明细', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('reservation.create', 'reservation', 'reservation', 'create', '创建预约', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('reservation.cancel', 'reservation', 'reservation', 'cancel', '取消预约', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('resource.manage', 'resource', 'resource', 'manage', '资源管理', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('resource.occupy', 'resource', 'resource', 'occupy', '资源占用', 'ACTIVE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE
  `module` = VALUES(`module`), `resource` = VALUES(`resource`), `action` = VALUES(`action`),
  `updated_at` = `iam_permission`.`updated_at`;

-- 权限作用域元数据（原 V28 的无 WHERE 全表 UPDATE，逐字保留其口径）。
--   【执行位置】必须严格等于原 V28 的位置：晚于 V23 之前的所有权限 INSERT，
--   早于 V25/V26/V30 的新增权限。因此它落在「预约履约/订单加项 + KTV 业务闭环」之后、
--   「仓库 / 商品权限」之前。位置错了会产生两处数据偏差：
--     1) 早于 V22：ktv.session.open / ktv.server.end 会被改写成 domain_code='ktv'，
--        而原库中这两行是在 V28 之后插入的，保留 DDL 列默认 domain_code='core'；
--     2) 晚于 V30：会覆盖 V30 显式登记的 scope_level='STORE' 与 grantable_levels='STORE'。
--   口径：platform.* 权限为 PLATFORM 作用域且只能授予 PLATFORM；
--         tenant.tenant.manage / tenant.store.manage / iam.role.manage 只能授予 TENANT；
--         其余为 TENANT/STORE。ktv.* 归 ktv 业务域，其余归 core。
UPDATE `iam_permission`
SET `scope_level` = CASE WHEN `code` LIKE 'platform.%' THEN 'PLATFORM' ELSE 'TENANT' END,
    `domain_code` = CASE WHEN `code` LIKE 'ktv.%' THEN 'ktv' ELSE 'core' END,
    `grantable_levels` = CASE
      WHEN `code` LIKE 'platform.%' THEN 'PLATFORM'
      WHEN `code` IN ('tenant.tenant.manage', 'tenant.store.manage', 'iam.role.manage') THEN 'TENANT'
      ELSE 'TENANT,STORE'
    END,
    `updated_at` = NOW(3);

-- -------------------------------------------------------------------------------------
-- 3.9 IAM 预置角色
--   tenant_id NULL 的唯一键不判定 NULL，用 NOT EXISTS 幂等（原 V3 + V11）。
-- -------------------------------------------------------------------------------------
INSERT INTO `iam_role`
  (`tenant_id`, `code`, `name`, `role_type`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT NULL, 'platform.operator', '平台运营', 'PRESET', 'ACTIVE', 0, NOW(3), 0, NOW(3)
WHERE NOT EXISTS (SELECT 1 FROM `iam_role` WHERE `code` = 'platform.operator' AND `tenant_id` IS NULL);
INSERT INTO `iam_role`
  (`tenant_id`, `code`, `name`, `role_type`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT NULL, 'tenant.owner', '租户老板', 'PRESET', 'ACTIVE', 0, NOW(3), 0, NOW(3)
WHERE NOT EXISTS (SELECT 1 FROM `iam_role` WHERE `code` = 'tenant.owner' AND `tenant_id` IS NULL);
INSERT INTO `iam_role`
  (`tenant_id`, `code`, `name`, `role_type`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT NULL, 'store.manager', '店长', 'PRESET', 'ACTIVE', 0, NOW(3), 0, NOW(3)
WHERE NOT EXISTS (SELECT 1 FROM `iam_role` WHERE `code` = 'store.manager' AND `tenant_id` IS NULL);
INSERT INTO `iam_role`
  (`tenant_id`, `code`, `name`, `role_type`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT NULL, 'store.cashier', '收银员', 'PRESET', 'ACTIVE', 0, NOW(3), 0, NOW(3)
WHERE NOT EXISTS (SELECT 1 FROM `iam_role` WHERE `code` = 'store.cashier' AND `tenant_id` IS NULL);
INSERT INTO `iam_role`
  (`tenant_id`, `code`, `name`, `role_type`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT NULL, 'store.finance', '财务', 'PRESET', 'ACTIVE', 0, NOW(3), 0, NOW(3)
WHERE NOT EXISTS (SELECT 1 FROM `iam_role` WHERE `code` = 'store.finance' AND `tenant_id` IS NULL);

-- 仓库 / 商品权限（原 V23）
INSERT INTO `iam_permission`
  (`code`, `module`, `resource`, `action`, `description`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('inventory.material.view', 'inventory', 'material', 'view', '查看仓库物料', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('inventory.material.manage', 'inventory', 'material', 'manage', '维护仓库物料', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('inventory.receipt.create', 'inventory', 'receipt', 'create', '物料入库', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('inventory.adjust', 'inventory', 'stock', 'adjust', '库存调整出入库', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('inventory.transaction.view', 'inventory', 'transaction', 'view', '查看库存流水', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('inventory.recovery.view', 'inventory', 'recovery', 'view', '查看作废订单库存处理项', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('inventory.recovery.confirm', 'inventory', 'recovery', 'confirm', '确认作废订单库存回补', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('product.view', 'product', 'product', 'view', '查看商品', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('product.manage', 'product', 'product', 'manage', '维护商品', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('product.publish', 'product', 'product', 'publish', '商品上架', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('product.unpublish', 'product', 'product', 'unpublish', '商品下架', 'ACTIVE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE
  `module` = VALUES(`module`), `resource` = VALUES(`resource`), `action` = VALUES(`action`),
  `updated_at` = `iam_permission`.`updated_at`;

-- 审计日志查询权限（原 V25）：平台云供应后台看全部租户，租户后台管理员只看本租户。
--   * 权限码只用 audit.view（不区分平台/租户码），平台与租户的可见范围由**服务端**按签名运营上下文
--     的 scopeType=PLATFORM / 租户上下文强制收敛，前端传 tenantId 不能越权。
INSERT INTO `iam_permission`
  (`code`, `module`, `resource`, `action`, `description`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('audit.view', 'audit', 'audit', 'view', '查看操作/审计日志', 'ACTIVE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE
  `module` = VALUES(`module`), `resource` = VALUES(`resource`), `action` = VALUES(`action`),
  `description` = VALUES(`description`), `status` = 'ACTIVE', `updated_at` = NOW(3);

-- 租户币种管理权限（原 V26，docs/standards/16_CURRENCY_CONVENTIONS.md §2）。
-- 币种是租户级唯一来源（tnt_tenant_config: tenant_id + store_id=0 + config_key='currency'）。
INSERT INTO `iam_permission`
  (`code`, `module`, `resource`, `action`, `description`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('tenant.currency.manage', 'tenant', 'currency', 'manage', '修改租户币种', 'ACTIVE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE
  `module` = VALUES(`module`), `resource` = VALUES(`resource`), `action` = VALUES(`action`),
  `description` = VALUES(`description`), `status` = 'ACTIVE', `updated_at` = NOW(3);

-- 2.2.0 Customer / HQ 权限登记（原 V30）：权限码是唯一权威，不由菜单三列推导。
INSERT INTO `iam_permission`
  (`code`, `module`, `resource`, `action`, `description`, `status`,
   `scope_level`, `domain_code`, `grantable_levels`, `menu_code`,
   `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('tenant.overview.view', 'tenant', 'overview', 'view', '总部经营总览', 'ACTIVE', 'TENANT', 'core', 'TENANT', 'tenant.overview', 0, NOW(3), 0, NOW(3)),
  ('member.view', 'member', 'member', 'view', '客户查看', 'ACTIVE', 'STORE', 'core', 'STORE', 'member', 0, NOW(3), 0, NOW(3)),
  ('member.manage', 'member', 'member', 'manage', '客户管理', 'ACTIVE', 'STORE', 'core', 'STORE', 'member', 0, NOW(3), 0, NOW(3)),
  ('points.view', 'points', 'points', 'view', '积分查看', 'ACTIVE', 'STORE', 'core', 'STORE', 'points', 0, NOW(3), 0, NOW(3)),
  ('points.adjust', 'points', 'points', 'adjust', '积分调整', 'ACTIVE', 'STORE', 'core', 'STORE', 'points', 0, NOW(3), 0, NOW(3)),
  ('wallet.view', 'wallet', 'wallet', 'view', '储值查看', 'ACTIVE', 'STORE', 'core', 'STORE', 'wallet', 0, NOW(3), 0, NOW(3)),
  ('wallet.recharge', 'wallet', 'wallet', 'recharge', '储值充值', 'ACTIVE', 'STORE', 'core', 'STORE', 'wallet', 0, NOW(3), 0, NOW(3)),
  ('wallet.refund', 'wallet', 'wallet', 'refund', '储值退款', 'ACTIVE', 'STORE', 'core', 'STORE', 'wallet', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE
  `module` = VALUES(`module`), `resource` = VALUES(`resource`), `action` = VALUES(`action`),
  `description` = VALUES(`description`), `scope_level` = VALUES(`scope_level`),
  `domain_code` = VALUES(`domain_code`), `grantable_levels` = VALUES(`grantable_levels`),
  `menu_code` = VALUES(`menu_code`), `updated_at` = `iam_permission`.`updated_at`;

-- -------------------------------------------------------------------------------------
-- 3.10 平台运营补齐「当前全部有效经营权限」（原 V24）
--   背景：SaaS 后台的租户后台菜单（门店/仓库/商品/订单/收银/KTV 等）对平台运营同样可见，
--   而平台超管 admin 是通过绑定 platform.operator 取得经营权限的
--   （见 docs/business/ACCOUNT_PERMISSION_MODEL.md §9.1：SUPER_ADMIN 不覆盖 platform.operator）。
--   此前 platform.operator 只有 tenant/iam/member 四项权限，平台运营进入租户上下文后，
--   仓库等业务接口一律 403 PERMISSION_DENIED。这里把当前全部有效的经营权限码授予
--   platform.operator，与「SUPER_ADMIN 全权限」一致；后续新增权限码需按需单独授权
--   （本语句在 V24 的原位置执行，故取到的是当时已存在的权限码，不含 V25/V26/V30 新增项）。
-- -------------------------------------------------------------------------------------
INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT `r`.`id`, `p`.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` `r`
JOIN `iam_permission` `p` ON `p`.`status` = 'ACTIVE'
WHERE `r`.`code` = 'platform.operator'
  AND `r`.`tenant_id` IS NULL
  AND `r`.`status` = 'ACTIVE'
  AND NOT EXISTS (
    SELECT 1 FROM `iam_role_permission` `rp`
    WHERE `rp`.`role_id` = `r`.`id` AND `rp`.`permission_id` = `p`.`id`
  );

-- -------------------------------------------------------------------------------------
-- 3.11 角色权限绑定（原 V3 + V8 + V9 + V11 + V13 + V17~V30 的累计最终结果）
--   口径：租户老板拥有全部经营权限；店长/收银/财务按职责收敛；role↔permission 唯一。
-- -------------------------------------------------------------------------------------
-- 租户老板
INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT `r`.`id`, `p`.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` `r` JOIN `iam_permission` `p` ON `p`.`code` IN (
  'tenant.tenant.manage', 'tenant.store.manage', 'iam.role.manage',
  'ktv.session.operate', 'ktv.session.correct_pause', 'ktv.server.order', 'ktv.session.open', 'ktv.server.end',
  'order.settle', 'order.hold', 'order.transfer', 'order.void', 'order.add_item', 'order.view',
  'payment.collect', 'payment.refund.approve', 'payment.refund.offline',
  'member.pii.view', 'reservation.view', 'reservation.confirm', 'reservation.arrival',
  'reservation.create', 'reservation.cancel', 'resource.manage', 'resource.occupy',
  'inventory.material.view', 'inventory.material.manage', 'inventory.receipt.create', 'inventory.adjust',
  'inventory.transaction.view', 'inventory.recovery.view', 'inventory.recovery.confirm',
  'product.view', 'product.manage', 'product.publish', 'product.unpublish',
  'audit.view', 'tenant.currency.manage',
  'tenant.overview.view', 'member.view', 'member.manage', 'points.view', 'points.adjust',
  'wallet.view', 'wallet.recharge', 'wallet.refund')
WHERE `r`.`code` = 'tenant.owner'
  AND `r`.`status` = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM `iam_role_permission` `rp` WHERE `rp`.`role_id` = `r`.`id` AND `rp`.`permission_id` = `p`.`id`);

-- 店长
INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT `r`.`id`, `p`.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` `r` JOIN `iam_permission` `p` ON `p`.`code` IN (
  'tenant.store.manage',
  'ktv.session.operate', 'ktv.session.correct_pause', 'ktv.server.order', 'ktv.session.open', 'ktv.server.end',
  'order.settle', 'order.hold', 'order.transfer', 'order.void', 'order.add_item', 'order.view',
  'payment.collect', 'payment.refund.approve', 'payment.refund.offline',
  'member.pii.view', 'reservation.view', 'reservation.confirm', 'reservation.arrival',
  'reservation.create', 'reservation.cancel', 'resource.manage', 'resource.occupy',
  'inventory.material.view', 'inventory.material.manage', 'inventory.receipt.create', 'inventory.adjust',
  'inventory.transaction.view', 'inventory.recovery.view', 'inventory.recovery.confirm',
  'product.view', 'product.manage', 'product.publish', 'product.unpublish',
  'member.view', 'member.manage', 'points.view', 'points.adjust',
  'wallet.view', 'wallet.recharge', 'wallet.refund')
WHERE `r`.`code` = 'store.manager'
  AND `r`.`status` = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM `iam_role_permission` `rp` WHERE `rp`.`role_id` = `r`.`id` AND `rp`.`permission_id` = `p`.`id`);

-- 收银员
INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT `r`.`id`, `p`.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` `r` JOIN `iam_permission` `p` ON `p`.`code` IN (
  'ktv.session.operate', 'ktv.server.order', 'ktv.session.open', 'ktv.server.end',
  'order.settle', 'order.hold', 'order.transfer', 'order.add_item', 'order.view',
  'payment.collect',
  'reservation.view', 'reservation.confirm', 'reservation.arrival', 'reservation.create',
  'resource.occupy',
  'member.view', 'points.view', 'wallet.view', 'wallet.recharge')
WHERE `r`.`code` = 'store.cashier'
  AND `r`.`status` = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM `iam_role_permission` `rp` WHERE `rp`.`role_id` = `r`.`id` AND `rp`.`permission_id` = `p`.`id`);

-- 财务
INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT `r`.`id`, `p`.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` `r` JOIN `iam_permission` `p` ON `p`.`code` IN (
  'order.void', 'order.view', 'payment.refund.approve', 'payment.refund.offline',
  'member.pii.view',
  'member.view', 'points.view', 'wallet.view', 'wallet.refund')
WHERE `r`.`code` = 'store.finance'
  AND `r`.`status` = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM `iam_role_permission` `rp` WHERE `rp`.`role_id` = `r`.`id` AND `rp`.`permission_id` = `p`.`id`);

-- 平台运营：显式补授审计与币种权限（原 V25/V26，与 3.10 全量授权同口径，避免依赖历史批量授权）
INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT `r`.`id`, `p`.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` `r` JOIN `iam_permission` `p` ON `p`.`code` IN ('audit.view', 'tenant.currency.manage')
WHERE `r`.`code` = 'platform.operator'
  AND `r`.`tenant_id` IS NULL
  AND `r`.`status` = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM `iam_role_permission` `rp` WHERE `rp`.`role_id` = `r`.`id` AND `rp`.`permission_id` = `p`.`id`);

-- 平台运营：租户级 Customer / HQ 能力（原 V30）
INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT `r`.`id`, `p`.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` `r` JOIN `iam_permission` `p` ON `p`.`code` IN (
  'tenant.overview.view', 'member.view', 'member.manage', 'points.view', 'points.adjust',
  'wallet.view', 'wallet.recharge', 'wallet.refund')
WHERE `r`.`code` = 'platform.operator'
  AND `r`.`tenant_id` IS NULL
  AND `r`.`status` = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM `iam_role_permission` `rp` WHERE `rp`.`role_id` = `r`.`id` AND `rp`.`permission_id` = `p`.`id`);

-- -------------------------------------------------------------------------------------
-- 3.12 用户角色分配
--   a380-admin(account_id=100) 绑定 tenant.owner（租户级，不得涉及 SaaS 平台级权限）
--   admin(account_id=1) 绑定 platform.operator（平台级作用域 PLATFORM）
-- -------------------------------------------------------------------------------------
INSERT INTO `iam_user_role`
  (`account_id`, `tenant_id`, `organization_id`, `store_id`, `role_id`, `scope_type`,
   `status`, `authorization_version`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT 100, 100, NULL, NULL, `r`.`id`, 'TENANT', 'ACTIVE', 1, 0, NOW(3), 0, NOW(3)
FROM `iam_role` `r` WHERE `r`.`code` = 'tenant.owner'
  AND NOT EXISTS (SELECT 1 FROM `iam_user_role` `ur`
                  WHERE `ur`.`account_id` = 100 AND `ur`.`tenant_id` = 100 AND `ur`.`role_id` = `r`.`id` AND `ur`.`status` = 'ACTIVE');

INSERT INTO `iam_user_role`
  (`account_id`, `tenant_id`, `organization_id`, `store_id`, `role_id`, `scope_type`,
   `status`, `authorization_version`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT 1, NULL, NULL, NULL, `r`.`id`, 'PLATFORM', 'ACTIVE', 1, 0, NOW(3), 0, NOW(3)
FROM `iam_role` `r` WHERE `r`.`code` = 'platform.operator'
  AND NOT EXISTS (SELECT 1 FROM `iam_user_role` `ur`
                  WHERE `ur`.`account_id` = 1 AND `ur`.`role_id` = `r`.`id` AND `ur`.`scope_type` = 'PLATFORM' AND `ur`.`status` = 'ACTIVE');

-- -------------------------------------------------------------------------------------
-- 3.13 消费端应用（企业安装授权的应用级授权，原 V14 + V20）
-- -------------------------------------------------------------------------------------
INSERT INTO `iam_consumer_application`
  (`app_id`, `tenant_id`, `organization_id`, `store_id`, `permissions_json`, `authorization_version`,
   `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('saas-a380-c', 100, 100, 100, '["reservation.view","reservation.create"]', 1, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('saas-a380-h5', 100, 100, 100, '["reservation.view","reservation.create"]', 1, 'ACTIVE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE
  `tenant_id` = VALUES(`tenant_id`), `organization_id` = VALUES(`organization_id`), `store_id` = VALUES(`store_id`),
  `permissions_json` = VALUES(`permissions_json`), `authorization_version` = VALUES(`authorization_version`),
  `status` = VALUES(`status`), `updated_at` = `iam_consumer_application`.`updated_at`;


-- =====================================================================================
-- 第 4 部分：数据回填（幂等语句，保留自原 V15 / V27）
-- =====================================================================================

-- 4.1 iam_role 作用域元数据回填（原 V28）
--   口径：platform.operator → PLATFORM；store.* → STORE；其余 → TENANT。
UPDATE `iam_role`
SET `scope_level` = CASE
      WHEN `code` = 'platform.operator' THEN 'PLATFORM'
      WHEN `code` LIKE 'store.%' THEN 'STORE'
      ELSE 'TENANT'
    END,
    `domain_code` = 'core',
    `updated_at` = NOW(3);

-- 4.2 iam_user_role 授权版本归一（原 V15）：有效授权至少为版本 1。
UPDATE `iam_user_role`
SET `authorization_version` = 1,
    `updated_at` = NOW(3)
WHERE `status` = 'ACTIVE'
  AND `authorization_version` <= 0;

-- 4.3 门店时区回填（原 V27）
--   缺省链与 docs/renovation/MULTI_TIMEZONE_DESIGN.md §2.1 一致：门店 → 租户 default_timezone → 平台默认 Asia/Shanghai。
UPDATE `tnt_store` `s`
LEFT JOIN `tnt_tenant` `t` ON `t`.`id` = `s`.`tenant_id`
SET `s`.`timezone` = COALESCE(NULLIF(TRIM(`s`.`timezone`), ''), NULLIF(TRIM(`t`.`default_timezone`), ''), 'Asia/Shanghai')
WHERE `s`.`timezone` IS NULL OR TRIM(`s`.`timezone`) = '';

-- 4.4 营业日切点回填（原 V27）：缺省 04:00:00（KTV 通宵场次归前一营业日，设计文档 §3.1）。
UPDATE `tnt_store` SET `business_day_cutoff` = '04:00:00' WHERE `business_day_cutoff` IS NULL;
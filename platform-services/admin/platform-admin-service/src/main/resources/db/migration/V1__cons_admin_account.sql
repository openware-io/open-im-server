-- 平台管理后台基线（platform-admin 域）：SaaS 后台管理员账号（cons_admin_account）与菜单定义（iam_menu）
-- 仅适用于未执行该基线的新建数据库：本文件是原 V1~V11 的累积终态，清库重建时由 Flyway 从 V1 一次性建齐。
-- 口径依据：SAAS 后台账号体系 item 1（管理员表 + IDaaS 映射 + 后端鉴权）；SAAS_MENU_PERMISSION_02_SERVICE.md §9.1。
-- 合并来源：V1 建 cons_admin_account；V2 种子 admin；V3 种子 a380-admin；V4 +platform_account_id 并回填；
--           V5 +唯一键(platform_account_id)；V6 +im_account 与唯一键；V7 建 iam_menu 并播种 26 条菜单；
--           V8 pricing 入口改为 KTV 配置；V9 菜单分段收口（id 改自增、补声明式权限列、重建父子关系）；
--           V10/V11 储值管理归门店经营、代币配置归租户总部。
-- 规范化：每表补齐 4 个审计字段（created_by / created_at / updated_by / updated_at）；索引名统一为
--           <完整表名>_<语义>；标识符统一加反引号；时间列统一 datetime(3)；补齐表与列注释。
-- 表名保持原样：iam_ / saa_ 前缀属挂起的前缀决策（见 docs/renovation/MIGRATION_BASELINE_01_HANDOFF.md §2），
--           本文件不重命名任何表，也不新增前缀登记。

-- SaaS 后台管理员账号：平台/租户运营人员的后台登录主体。idaas_subject 关联集团账号做 SSO；
-- platform_account_id 关联 idt_account.id，是经营 IAM（iam_user_role.account_id）的账号口径；
-- im_account 为运营人员的 IM 绑定，同一账号唯一、解绑后置空。
CREATE TABLE `cons_admin_account` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '管理员账号ID',
  `username` varchar(64) NOT NULL COMMENT '登录用户名',
  `password_hash` varchar(100) NULL COMMENT 'BCrypt 密码哈希（SSO 映射账号可为空）',
  `display_name` varchar(128) NOT NULL COMMENT '展示名',
  `im_account` varchar(64) NULL COMMENT '绑定的 IM 账号（运营人员，解绑后置空）',
  `idaas_subject` varchar(128) NULL COMMENT '集团 IDaaS idaas_account.id（SSO 映射键）',
  `platform_account_id` bigint unsigned NULL COMMENT '关联 SaaS 平台账号 idt_account.id',
  `role` varchar(32) NOT NULL DEFAULT 'SUPER_ADMIN' COMMENT 'SUPER_ADMIN/PLATFORM_ADMIN/TENANT_ADMIN',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/SUSPENDED/CLOSED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cons_admin_account_username` (`username`),
  UNIQUE KEY `uk_cons_admin_account_im_account` (`im_account`),
  UNIQUE KEY `uk_cons_admin_account_platform_account` (`platform_account_id`),
  KEY `idx_cons_admin_account_idaas_subject` (`idaas_subject`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SaaS后台管理员账号';

-- 菜单定义：平台（PLATFORM）/ 租户总部（TENANT）/ 门店经营（STORE）三段作用域，一个节点一行；
-- 可见性声明式化：required_permission 校验权限码、required_grant 校验能力授予、business_type 限定业态。
-- 唯一键 (scope_level, code)：同一语义码可在不同作用域复用（如 PLATFORM 与 TENANT 各有一个 paymethod）。
CREATE TABLE `iam_menu` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
  `parent_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '父菜单ID，0=根节点',
  `code` varchar(128) NOT NULL COMMENT '菜单语义码（同作用域内唯一）',
  `name` varchar(128) NOT NULL COMMENT '菜单名称',
  `path` varchar(255) NULL COMMENT '前端路由路径，NULL=仅作分组不跳转',
  `icon` varchar(64) NULL COMMENT '图标标识',
  `scope_level` varchar(32) NOT NULL COMMENT '作用域 PLATFORM/TENANT/STORE',
  `domain_code` varchar(64) NOT NULL COMMENT '功能域 core/ktv 等',
  `required_permission` varchar(128) NULL COMMENT '可见所需权限码，NULL=不校验',
  `required_grant` varchar(128) NULL COMMENT '可见所需能力授予，NULL=不校验',
  `business_type` varchar(32) NULL COMMENT '业态限定 KTV 等，NULL=通用',
  `i18n_key` varchar(128) NULL COMMENT '国际化键，NULL=用 name',
  `sort_no` int NOT NULL DEFAULT 0 COMMENT '同层排序号',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/INACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_menu_scope_code` (`scope_level`, `code`),
  KEY `idx_iam_menu_scope_sort` (`scope_level`, `sort_no`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台/租户/门店菜单定义';

-- 种子数据：本地启动所需的最小数据。管理员口令为 BCrypt 真实哈希，初始口令明文不落在迁移脚本中。

INSERT INTO `cons_admin_account`
  (`username`, `password_hash`, `display_name`, `im_account`, `idaas_subject`, `platform_account_id`,
   `role`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('admin', '$2b$10$uj5EtFLiaUGBRWv5gbSFfeC85AT5OTIJuTzYUZ7PUD/StOSsCeXtS', '管理员', NULL, '1', 1,
   'SUPER_ADMIN', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('a380-admin', '$2b$10$uj5EtFLiaUGBRWv5gbSFfeC85AT5OTIJuTzYUZ7PUD/StOSsCeXtS', 'A380 管理员', NULL, NULL, 100,
   'TENANT_ADMIN', 'ACTIVE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE `updated_at` = `updated_at`;

-- 菜单最终态：租户总部（100~103）与门店经营（200~205）两段由功能域父节点承载；
-- id=2 的旧 pricing 入口与 id=16 的 KTV 配置重复，保留 id=16 可达、id=2 置 INACTIVE；
-- id=21 储值管理归门店客户资产（wallet.view）；id=30 代币配置归租户总部资金与支付。
INSERT INTO `iam_menu`
  (`id`, `parent_id`, `code`, `name`, `path`, `icon`, `scope_level`, `domain_code`, `required_permission`,
   `required_grant`, `business_type`, `i18n_key`, `sort_no`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  (1, 0, 'tenant', '租户管理', '/admin/platform/tenants', 'building', 'PLATFORM', 'core', NULL, NULL, NULL, NULL, 10, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (2, 0, 'pricing', 'KTV 配置', '/admin/ktv/config', 'setting', 'TENANT', 'ktv', NULL, NULL, NULL, NULL, 45, 'INACTIVE', 0, NOW(3), 0, NOW(3)),
  (3, 0, 'iam', '权限管理', '/admin/iam', 'management', 'PLATFORM', 'core', NULL, NULL, NULL, NULL, 30, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (4, 0, 'paymethod', '支付方式', '/admin/platform/payment-methods', 'money', 'PLATFORM', 'core', NULL, NULL, NULL, NULL, 40, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (10, 100, 'store', '门店', '/admin/tenant/stores', 'shop', 'TENANT', 'core', NULL, NULL, NULL, NULL, 10, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (11, 201, 'resource', '包厢管理', '/admin/resources', 'grid', 'STORE', 'core', NULL, NULL, NULL, NULL, 10, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (12, 200, 'reservation', '预约管理', '/business/reservations', 'calendar', 'STORE', 'core', NULL, NULL, NULL, NULL, 10, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (13, 200, 'order', '收银台', '/business/orders', 'tickets', 'STORE', 'core', NULL, NULL, NULL, NULL, 10, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (14, 200, 'payment', '收银/支付', '/business/payments', 'credit-card', 'STORE', 'core', NULL, NULL, NULL, NULL, 10, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (15, 204, 'report', '报表', '/admin/reports', 'chart', 'STORE', 'core', NULL, NULL, NULL, NULL, 10, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (16, 103, 'ktv', 'KTV 配置', '/admin/ktv/config', 'setting', 'TENANT', 'ktv', NULL, NULL, 'KTV', NULL, 20, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (17, 200, 'shift', '交班/日结', '/business/shifts', 'clock', 'STORE', 'core', NULL, NULL, NULL, NULL, 10, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (18, 203, 'member', '客户管理', '/business/members', 'user', 'STORE', 'core', NULL, NULL, NULL, NULL, 10, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (19, 203, 'points', '积分管理', '/business/points', 'star', 'STORE', 'core', NULL, NULL, NULL, NULL, 20, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (20, 101, 'paymethod', '支付方式', '/business/payment-methods', 'collection', 'TENANT', 'core', NULL, NULL, NULL, NULL, 20, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (21, 203, 'wallet', '储值管理', '/business/wallet', 'coin', 'STORE', 'core', 'wallet.view', NULL, NULL, NULL, 30, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (22, 102, 'security', '脱敏权限', '/admin/security', 'lock', 'TENANT', 'core', 'iam.role.manage', NULL, NULL, NULL, 20, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (23, 102, 'staff', '运营人员', '/admin/staff', 'service', 'TENANT', 'core', 'iam.role.manage', NULL, NULL, NULL, 10, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (24, 202, 'inventory', '仓库管理', '/admin/inventory', 'box', 'STORE', 'core', NULL, NULL, NULL, NULL, 20, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (25, 202, 'products', '商品管理', '/admin/products', 'goods', 'STORE', 'core', NULL, NULL, NULL, NULL, 10, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (26, 103, 'audit', '审计日志', '/admin/audits', 'document', 'TENANT', 'core', 'audit.view', NULL, NULL, NULL, 10, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (27, 101, 'currency', '币种', '/admin/tenant/currency', 'wallet', 'TENANT', 'core', 'tenant.currency.manage', NULL, NULL, NULL, 10, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (28, 200, 'order-manage', '订单管理', '/admin/orders', 'list', 'STORE', 'core', NULL, NULL, NULL, NULL, 10, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (29, 0, 'tenant.overview', '经营总览', '/admin/tenant/overview', 'dashboard', 'TENANT', 'core', NULL, NULL, NULL, NULL, 10, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (30, 101, 'tenant.wallet-token-config', '代币配置', '/admin/tenant/wallet-token-config', 'setting', 'TENANT', 'core', 'tenant.tenant.manage', NULL, NULL, NULL, 30, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (31, 3, 'iam.role', '角色', '/admin/iam/roles', 'avatar', 'PLATFORM', 'core', NULL, NULL, NULL, NULL, 31, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (32, 3, 'iam.permission', '权限', '/admin/iam/permissions', 'key', 'PLATFORM', 'core', NULL, NULL, NULL, NULL, 32, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (100, 0, 'tenant.org', '组织与门店', NULL, 'office-building', 'TENANT', 'core', NULL, NULL, NULL, NULL, 20, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (101, 0, 'tenant.finance', '资金与支付', NULL, 'wallet', 'TENANT', 'core', NULL, NULL, NULL, NULL, 30, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (102, 0, 'tenant.iam', '人员与权限', NULL, 'user', 'TENANT', 'core', NULL, NULL, NULL, NULL, 40, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (103, 0, 'tenant.settings', '租户设置', NULL, 'setting', 'TENANT', 'core', NULL, NULL, NULL, NULL, 50, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (200, 0, 'store.ops', '门店运营', NULL, 'shop', 'STORE', 'core', NULL, NULL, NULL, NULL, 10, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (201, 0, 'store.resource', '门店资源', NULL, 'grid', 'STORE', 'core', NULL, NULL, NULL, NULL, 20, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (202, 0, 'store.stock', '门店商品与库存', NULL, 'goods', 'STORE', 'core', NULL, NULL, NULL, NULL, 30, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (203, 0, 'store.crm', '客户与资产', NULL, 'user', 'STORE', 'core', NULL, NULL, NULL, NULL, 40, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (204, 0, 'store.report', '门店报表', NULL, 'data-analysis', 'STORE', 'core', NULL, NULL, NULL, NULL, 50, 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  (205, 0, 'store.settings', '门店设置', NULL, 'setting', 'STORE', 'core', NULL, NULL, NULL, NULL, 60, 'ACTIVE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE `updated_at` = `updated_at`;

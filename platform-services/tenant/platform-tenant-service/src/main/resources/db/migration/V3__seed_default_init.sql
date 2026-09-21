-- 默认初始化：默认租户 + 首组织 + 首门店(KTV) + 预置角色/权限（幂等：依赖唯一键重复跳过）
INSERT INTO tnt_tenant (tenant_code, name, status, default_locale, default_timezone, created_at, updated_at)
VALUES ('default', '默认租户', 'ACTIVE', 'zh-CN', 'Asia/Shanghai', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE updated_at = tnt_tenant.updated_at;

INSERT INTO tnt_organization (tenant_id, code, name, status, created_at, updated_at)
SELECT id, 'headquarter', '总部', 'ACTIVE', NOW(3), NOW(3) FROM tnt_tenant WHERE tenant_code = 'default'
ON DUPLICATE KEY UPDATE updated_at = tnt_organization.updated_at;

INSERT INTO tnt_store (tenant_id, organization_id, code, name, business_type, country_code, timezone, default_currency, locale, status, created_at, updated_at)
SELECT t.id, o.id, 'store-001', '首店', 'KTV', 'CN', 'Asia/Shanghai', 'CNY', 'zh-CN', 'ACTIVE', NOW(3), NOW(3)
FROM tnt_tenant t JOIN tnt_organization o ON o.tenant_id = t.id AND o.code = 'headquarter'
WHERE t.tenant_code = 'default'
ON DUPLICATE KEY UPDATE updated_at = tnt_store.updated_at;

INSERT INTO tnt_business_type (code, name, status, created_at, updated_at)
VALUES ('KTV', 'KTV', 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE updated_at = tnt_business_type.updated_at;

INSERT INTO iam_permission (code, module, resource, action, description, status, created_at, updated_at) VALUES
('tenant.tenant.manage', 'tenant', 'tenant', 'manage', '租户管理', 'ACTIVE', NOW(3), NOW(3)),
('tenant.store.manage', 'tenant', 'store', 'manage', '门店管理', 'ACTIVE', NOW(3), NOW(3)),
('iam.role.manage', 'iam', 'role', 'manage', '角色管理', 'ACTIVE', NOW(3), NOW(3)),
('ktv.session.operate', 'ktv', 'session', 'operate', 'KTV 开台/结台', 'ACTIVE', NOW(3), NOW(3)),
('ktv.server.order', 'ktv', 'server', 'order', '服务人员点单', 'ACTIVE', NOW(3), NOW(3)),
('payment.collect', 'payment', 'collect', 'operate', '组合收款', 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE updated_at = iam_permission.updated_at;

INSERT INTO iam_role (tenant_id, code, name, role_type, status, created_at, updated_at) VALUES
(NULL, 'platform.operator', '平台运营', 'PRESET', 'ACTIVE', NOW(3), NOW(3)),
(NULL, 'tenant.owner', '租户老板', 'PRESET', 'ACTIVE', NOW(3), NOW(3)),
(NULL, 'store.manager', '店长', 'PRESET', 'ACTIVE', NOW(3), NOW(3)),
(NULL, 'store.cashier', '收银员', 'PRESET', 'ACTIVE', NOW(3), NOW(3)),
(NULL, 'store.finance', '财务', 'PRESET', 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE updated_at = iam_role.updated_at;

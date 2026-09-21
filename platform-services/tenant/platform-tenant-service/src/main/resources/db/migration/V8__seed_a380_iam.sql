-- A380 租户管理员的经营作用域绑定
-- 原则：租户管理员作用范围只能是「租户级别」，不得涉及 SaaS 平台级权限（不授予 platform.operator）。
-- 角色 = tenant.owner（租户老板，PRESET 租户级角色）；作用域 = TENANT + tenant_id=100。
INSERT INTO iam_user_role (account_id, tenant_id, organization_id, store_id, role_id, scope_type, status, authorization_version, created_at, updated_at)
SELECT 100, 100, NULL, NULL, r.id, 'TENANT', 'ACTIVE', 0, NOW(3), NOW(3)
FROM iam_role r
WHERE r.code = 'tenant.owner'
  AND NOT EXISTS (SELECT 1 FROM iam_user_role ur
                  WHERE ur.account_id = 100 AND ur.tenant_id = 100 AND ur.role_id = r.id AND ur.status = 'ACTIVE');

-- 租户老板角色绑定「租户/门店级」权限（不含平台级权限；iam.role.manage 暂不授予，按需再配）
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.code IN ('tenant.tenant.manage', 'tenant.store.manage',
                                    'ktv.session.operate', 'ktv.server.order', 'payment.collect')
WHERE r.code = 'tenant.owner'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

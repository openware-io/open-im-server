-- 修复 IAM 运营权限种子：补齐 V3 缺失的 order/payment/ktv 权限码，
-- 幂等重建预置权限/角色/角色权限/用户角色（历史环境 iam_role/iam_permission 数据被清空）。
-- 仅作用于 SaaS 租户/门店级经营权限，不含平台级敏感权限。

-- 1) 权限码（code 唯一，幂等）
INSERT INTO iam_permission (code, module, resource, action, description, status, created_at, updated_at) VALUES
('tenant.tenant.manage','tenant','tenant','manage','租户管理','ACTIVE',NOW(3),NOW(3)),
('tenant.store.manage','tenant','store','manage','门店管理','ACTIVE',NOW(3),NOW(3)),
('iam.role.manage','iam','role','manage','角色管理','ACTIVE',NOW(3),NOW(3)),
('ktv.session.operate','ktv','session','operate','KTV 开台/结台','ACTIVE',NOW(3),NOW(3)),
('ktv.session.correct_pause','ktv','session','correct_pause','KTV 暂停修正','ACTIVE',NOW(3),NOW(3)),
('ktv.server.order','ktv','server','order','服务人员点单','ACTIVE',NOW(3),NOW(3)),
('order.settle','order','order','settle','订单结算','ACTIVE',NOW(3),NOW(3)),
('order.hold','order','order','hold','订单挂单/解挂','ACTIVE',NOW(3),NOW(3)),
('order.transfer','order','order','transfer','订单转台','ACTIVE',NOW(3),NOW(3)),
('order.void','order','order','void','订单作废','ACTIVE',NOW(3),NOW(3)),
('payment.collect','payment','collect','operate','组合收款','ACTIVE',NOW(3),NOW(3)),
('payment.refund.approve','payment','refund','approve','退款审批','ACTIVE',NOW(3),NOW(3)),
('payment.refund.offline','payment','refund','offline','线下退款','ACTIVE',NOW(3),NOW(3)),
('member.pii.view','member','pii','view','查看会员隐私信息(姓名/手机号明文)','ACTIVE',NOW(3),NOW(3))
ON DUPLICATE KEY UPDATE module = VALUES(module), resource = VALUES(resource), action = VALUES(action), updated_at = updated_at;

-- 2) 预置角色（tenant_id NULL 的唯一键不判定 NULL，用 NOT EXISTS 幂等）
INSERT INTO iam_role (tenant_id, code, name, role_type, status, created_at, updated_at)
SELECT NULL, 'platform.operator', '平台运营', 'PRESET', 'ACTIVE', NOW(3), NOW(3)
WHERE NOT EXISTS (SELECT 1 FROM iam_role WHERE code='platform.operator' AND tenant_id IS NULL);
INSERT INTO iam_role (tenant_id, code, name, role_type, status, created_at, updated_at)
SELECT NULL, 'tenant.owner', '租户老板', 'PRESET', 'ACTIVE', NOW(3), NOW(3)
WHERE NOT EXISTS (SELECT 1 FROM iam_role WHERE code='tenant.owner' AND tenant_id IS NULL);
INSERT INTO iam_role (tenant_id, code, name, role_type, status, created_at, updated_at)
SELECT NULL, 'store.manager', '店长', 'PRESET', 'ACTIVE', NOW(3), NOW(3)
WHERE NOT EXISTS (SELECT 1 FROM iam_role WHERE code='store.manager' AND tenant_id IS NULL);
INSERT INTO iam_role (tenant_id, code, name, role_type, status, created_at, updated_at)
SELECT NULL, 'store.cashier', '收银员', 'PRESET', 'ACTIVE', NOW(3), NOW(3)
WHERE NOT EXISTS (SELECT 1 FROM iam_role WHERE code='store.cashier' AND tenant_id IS NULL);
INSERT INTO iam_role (tenant_id, code, name, role_type, status, created_at, updated_at)
SELECT NULL, 'store.finance', '财务', 'PRESET', 'ACTIVE', NOW(3), NOW(3)
WHERE NOT EXISTS (SELECT 1 FROM iam_role WHERE code='store.finance' AND tenant_id IS NULL);

-- 3) 角色权限：租户老板拥有全部经营权限；店长/收银/财务按职责收敛
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM iam_role r JOIN iam_permission p ON p.code IN (
  'tenant.tenant.manage','tenant.store.manage','iam.role.manage',
  'ktv.session.operate','ktv.session.correct_pause','ktv.server.order',
  'order.settle','order.hold','order.transfer','order.void',
  'payment.collect','payment.refund.approve','payment.refund.offline','member.pii.view')
WHERE r.code='tenant.owner'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM iam_role r JOIN iam_permission p ON p.code IN (
  'tenant.store.manage','ktv.session.operate','ktv.session.correct_pause','ktv.server.order',
  'order.settle','order.hold','order.transfer','order.void',
  'payment.collect','payment.refund.approve','payment.refund.offline','member.pii.view')
WHERE r.code='store.manager'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM iam_role r JOIN iam_permission p ON p.code IN (
  'ktv.session.operate','ktv.server.order','order.settle','order.hold','order.transfer','payment.collect')
WHERE r.code='store.cashier'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM iam_role r JOIN iam_permission p ON p.code IN (
  'order.void','payment.refund.approve','payment.refund.offline','member.pii.view')
WHERE r.code='store.finance'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM iam_role r JOIN iam_permission p ON p.code IN (
  'tenant.tenant.manage','iam.role.manage','tenant.store.manage')
WHERE r.code='platform.operator'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);

-- 4) 用户角色：a380-admin(platform_account_id=100) 绑定 tenant.owner；admin(account_id=1) 绑定 platform.operator
INSERT INTO iam_user_role (account_id, tenant_id, organization_id, store_id, role_id, scope_type, status, authorization_version, created_at, updated_at)
SELECT 100, 100, NULL, NULL, r.id, 'TENANT', 'ACTIVE', 1, NOW(3), NOW(3)
FROM iam_role r WHERE r.code='tenant.owner'
  AND NOT EXISTS (SELECT 1 FROM iam_user_role ur WHERE ur.account_id=100 AND ur.tenant_id=100 AND ur.role_id=r.id AND ur.status='ACTIVE');
INSERT INTO iam_user_role (account_id, tenant_id, organization_id, store_id, role_id, scope_type, status, authorization_version, created_at, updated_at)
SELECT 1, NULL, NULL, NULL, r.id, 'PLATFORM', 'ACTIVE', 1, NOW(3), NOW(3)
FROM iam_role r WHERE r.code='platform.operator'
  AND NOT EXISTS (SELECT 1 FROM iam_user_role ur WHERE ur.account_id=1 AND ur.role_id=r.id AND ur.scope_type='PLATFORM' AND ur.status='ACTIVE');

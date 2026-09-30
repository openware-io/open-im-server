-- 2.2.0 Customer / HQ 权限登记：权限码是唯一权威，不由菜单三列推导。
INSERT INTO iam_permission (code, module, resource, action, description, status, created_at, updated_at,
                            scope_level, domain_code, grantable_levels, menu_code) VALUES
('tenant.overview.view','tenant','overview','view','总部经营总览','ACTIVE',NOW(3),NOW(3),'TENANT','core','TENANT','tenant.overview'),
('member.view','member','member','view','客户查看','ACTIVE',NOW(3),NOW(3),'STORE','core','STORE','member'),
('member.manage','member','member','manage','客户管理','ACTIVE',NOW(3),NOW(3),'STORE','core','STORE','member'),
('points.view','points','points','view','积分查看','ACTIVE',NOW(3),NOW(3),'STORE','core','STORE','points'),
('points.adjust','points','points','adjust','积分调整','ACTIVE',NOW(3),NOW(3),'STORE','core','STORE','points'),
('wallet.view','wallet','wallet','view','储值查看','ACTIVE',NOW(3),NOW(3),'STORE','core','STORE','wallet'),
('wallet.recharge','wallet','wallet','recharge','储值充值','ACTIVE',NOW(3),NOW(3),'STORE','core','STORE','wallet'),
('wallet.refund','wallet','wallet','refund','储值退款','ACTIVE',NOW(3),NOW(3),'STORE','core','STORE','wallet')
ON DUPLICATE KEY UPDATE module=VALUES(module), resource=VALUES(resource), action=VALUES(action),
  description=VALUES(description), scope_level=VALUES(scope_level), domain_code=VALUES(domain_code),
  grantable_levels=VALUES(grantable_levels), menu_code=VALUES(menu_code), updated_at=updated_at;

-- 租户老板与平台运营保留完整租户级能力；门店角色按职责收敛。
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM iam_role r JOIN iam_permission p
  ON p.code IN ('tenant.overview.view','member.view','member.manage','points.view','points.adjust',
                'wallet.view','wallet.recharge','wallet.refund')
WHERE r.code IN ('tenant.owner','platform.operator')
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM iam_role r JOIN iam_permission p
  ON p.code IN ('member.view','member.manage','points.view','points.adjust','wallet.view','wallet.recharge','wallet.refund')
WHERE r.code='store.manager'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM iam_role r JOIN iam_permission p
  ON p.code IN ('member.view','points.view','wallet.view','wallet.recharge')
WHERE r.code='store.cashier'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM iam_role r JOIN iam_permission p
  ON p.code IN ('member.view','points.view','wallet.view','wallet.refund')
WHERE r.code='store.finance'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);

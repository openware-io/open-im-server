-- 租户币种管理权限（docs/standards/16_CURRENCY_CONVENTIONS.md §2）。
--   * 币种是租户级唯一来源（tnt_tenant_config: tenant_id + store_id=0 + config_key='currency'），
--     平台运营可改任一租户、租户管理员可改本租户，因此两个角色都授予 tenant.currency.manage；
--   * 读接口只要求租户上下文（C 端也要读到本租户币种），不额外授予查看权限；
--   * 与 V25 同口径：platform.operator（tenant_id 为空）在此显式补授，不依赖历史批量授权。
INSERT INTO iam_permission (code, module, resource, action, description, status, created_at, updated_at) VALUES
('tenant.currency.manage','tenant','currency','manage','修改租户币种','ACTIVE',NOW(3),NOW(3))
ON DUPLICATE KEY UPDATE module=VALUES(module), resource=VALUES(resource), action=VALUES(action),
  description=VALUES(description), status='ACTIVE', updated_at=NOW(3);

-- 租户角色 tenant.owner：租户管理员可改本租户币种
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM iam_role r JOIN iam_permission p ON p.code = 'tenant.currency.manage'
WHERE r.code = 'tenant.owner'
  AND r.status = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);

-- 平台角色 platform.operator：平台运营在任意运营上下文都能改目标租户的币种
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM iam_role r JOIN iam_permission p ON p.code = 'tenant.currency.manage'
WHERE r.code = 'platform.operator'
  AND r.tenant_id IS NULL
  AND r.status = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);

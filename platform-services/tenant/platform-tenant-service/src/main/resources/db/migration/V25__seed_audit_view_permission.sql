-- 统一审计日志查询权限：平台云供应后台看全部租户，租户后台管理员只看本租户。
--   * 权限码只用 audit.view（不区分平台/租户码），平台与租户的可见范围由**服务端**按签名运营上下文
--     的 scopeType=PLATFORM / 租户上下文强制收敛，前端传 tenantId 不能越权；
--   * 平台角色 platform.operator（tenant_id 为空）与租户角色 tenant.owner 都授予该权限；
--   * 与 V24 同口径：platform.operator 也在此显式补授，避免依赖「全量经营权限」的历史批量授权。
INSERT INTO iam_permission (code, module, resource, action, description, status, created_at, updated_at) VALUES
('audit.view','audit','audit','view','查看操作/审计日志','ACTIVE',NOW(3),NOW(3))
ON DUPLICATE KEY UPDATE module=VALUES(module), resource=VALUES(resource), action=VALUES(action),
  description=VALUES(description), status='ACTIVE', updated_at=NOW(3);

-- 租户角色 tenant.owner：租户后台管理员可见本租户全部操作日志
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM iam_role r JOIN iam_permission p ON p.code = 'audit.view'
WHERE r.code = 'tenant.owner'
  AND r.status = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);

-- 平台角色 platform.operator：平台运营在任意运营上下文都能查看全部租户的审计日志
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM iam_role r JOIN iam_permission p ON p.code = 'audit.view'
WHERE r.code = 'platform.operator'
  AND r.tenant_id IS NULL
  AND r.status = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);

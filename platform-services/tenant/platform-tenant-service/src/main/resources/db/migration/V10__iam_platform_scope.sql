-- 支持平台级作用域：tenant_id 允许为空（平台级角色无租户），scope_type 增加 PLATFORM
ALTER TABLE iam_user_role MODIFY tenant_id bigint unsigned NULL;

-- 唯一超管 admin（account_id=1）绑定平台运营角色 platform.operator，作用域 PLATFORM
INSERT INTO iam_user_role (account_id, tenant_id, organization_id, store_id, role_id, scope_type, status, authorization_version, created_at, updated_at)
SELECT 1, NULL, NULL, NULL, r.id, 'PLATFORM', 'ACTIVE', 0, NOW(3), NOW(3)
FROM iam_role r
WHERE r.code = 'platform.operator'
  AND NOT EXISTS (SELECT 1 FROM iam_user_role ur
                  WHERE ur.account_id = 1 AND ur.role_id = r.id AND ur.scope_type = 'PLATFORM' AND ur.status = 'ACTIVE');

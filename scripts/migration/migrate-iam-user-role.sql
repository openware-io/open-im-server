START TRANSACTION;
-- 迁移 iam_user_role 到 iam_role_binding（S2，幂等）。
-- 在目标 SaaS 库执行；不自动创建 membership。
-- 规则：
--   PLATFORM -> subject_type=ACCOUNT, subject_id=account_id, tenant/org/store=0
--   TENANT/ORGANIZATION/STORE/SELF -> 必须有 STAFF membership；subject_type=MEMBERSHIP
--   SELF 统一 org/store=0（自身数据范围由权限判定）
--   找不到 STAFF membership 的旧行不迁移，末尾报告。

INSERT INTO iam_role_binding
  (subject_type, subject_id, role_id, scope_type, tenant_id, organization_id, store_id,
   authorization_version, effective_from, effective_to, status, created_by, created_at, updated_by, updated_at)
SELECT
  'ACCOUNT', ur.account_id, ur.role_id, ur.scope_type, 0, 0, 0,
  GREATEST(ur.authorization_version, 1), ur.effective_from, ur.effective_to,
  ur.status, ur.created_by, ur.created_at, ur.updated_by, ur.updated_at
FROM iam_user_role_archive ur
WHERE ur.scope_type = 'PLATFORM'
  AND ur.status = 'ACTIVE'
  AND NOT EXISTS (
    SELECT 1 FROM iam_role_binding b
    WHERE b.subject_type = 'ACCOUNT' AND b.subject_id = ur.account_id
      AND b.role_id = ur.role_id AND b.scope_type = ur.scope_type
      AND b.tenant_id = 0 AND b.organization_id = 0 AND b.store_id = 0
  );

INSERT INTO iam_role_binding
  (subject_type, subject_id, role_id, scope_type, tenant_id, organization_id, store_id,
   authorization_version, effective_from, effective_to, status, created_by, created_at, updated_by, updated_at)
SELECT
  'MEMBERSHIP', mem.id, ur.role_id, ur.scope_type, ur.tenant_id,
  CASE WHEN ur.scope_type = 'SELF' THEN 0 ELSE COALESCE(ur.organization_id, 0) END,
  CASE WHEN ur.scope_type = 'SELF' THEN 0 ELSE COALESCE(ur.store_id, 0) END,
  GREATEST(ur.authorization_version, mem.authorization_version, 1),
  ur.effective_from, ur.effective_to,
  ur.status, ur.created_by, ur.created_at, ur.updated_by, ur.updated_at
FROM iam_user_role_archive ur
JOIN tnt_membership mem
  ON mem.account_id = ur.account_id
 AND mem.tenant_id = ur.tenant_id
 AND mem.membership_type = 'STAFF'
WHERE ur.scope_type IN ('TENANT', 'ORGANIZATION', 'STORE', 'SELF')
  AND ur.status = 'ACTIVE'
  AND NOT EXISTS (
    SELECT 1 FROM iam_role_binding b
    WHERE b.subject_type = 'MEMBERSHIP' AND b.subject_id = mem.id
      AND b.role_id = ur.role_id AND b.scope_type = ur.scope_type
      AND b.tenant_id = ur.tenant_id
      AND b.organization_id = CASE WHEN ur.scope_type = 'SELF' THEN 0 ELSE COALESCE(ur.organization_id, 0) END
      AND b.store_id = CASE WHEN ur.scope_type = 'SELF' THEN 0 ELSE COALESCE(ur.store_id, 0) END
  );

COMMIT;

-- 未迁移报告：非平台旧绑定找不到 STAFF membership
SELECT ur.id, ur.account_id, ur.tenant_id, ur.role_id, ur.scope_type
FROM iam_user_role_archive ur
LEFT JOIN tnt_membership mem
  ON mem.account_id = ur.account_id
 AND mem.tenant_id = ur.tenant_id
 AND mem.membership_type = 'STAFF'
WHERE ur.scope_type <> 'PLATFORM'
  AND ur.status = 'ACTIVE'
  AND mem.id IS NULL
ORDER BY ur.id;

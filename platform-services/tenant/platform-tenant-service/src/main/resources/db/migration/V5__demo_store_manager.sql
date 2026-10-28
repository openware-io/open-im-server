-- 本地/演示环境：给 A380 管理员补一个门店店长角色，便于验证门店动作权限。
-- 生产环境由租户管理员通过运营人员/角色分配完成，不依赖此种子。
INSERT INTO `iam_user_role`
  (`account_id`, `tenant_id`, `organization_id`, `store_id`, `role_id`, `scope_type`,
   `effective_from`, `status`, `authorization_version`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT 100, 100, 100, 100, r.`id`, 'STORE', NOW(3), 'ACTIVE', 1, 0, NOW(3), 0, NOW(3)
FROM `iam_role` r
WHERE r.`code` = 'store.manager'
  AND NOT EXISTS (
    SELECT 1 FROM `iam_user_role` ur
    WHERE ur.`account_id` = 100 AND ur.`tenant_id` = 100 AND ur.`store_id` = 100
      AND ur.`role_id` = r.`id` AND ur.`status` = 'ACTIVE'
  );

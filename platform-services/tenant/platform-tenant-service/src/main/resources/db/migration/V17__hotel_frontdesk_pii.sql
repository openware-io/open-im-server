-- 前台角色需要查看住客联系方式；财务/无此权限角色仍按 PII 掩码。
INSERT INTO `iam_role_permission`
  (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT r.id, p.id, 0, NOW(3), 0, NOW(3)
FROM `iam_role` r
JOIN `iam_permission` p ON p.code = 'member.pii.view'
WHERE r.code IN ('store.manager', 'store.cashier') AND r.tenant_id IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM `iam_role_permission` rp
     WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 会员隐私信息查看权限：拥有者回显姓名/手机号明文，无权限者脱敏（member.pii.view）
INSERT INTO iam_permission (code, module, resource, action, description, status, created_at, updated_at)
VALUES ('member.pii.view', 'member', 'pii', 'view', '查看会员隐私信息(姓名/手机号明文)', 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE updated_at = iam_permission.updated_at;

-- 租户老板 + 店长 可查看会员隐私明文（平台级/收银员不授予，默认脱敏）
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.code = 'member.pii.view'
WHERE r.code IN ('tenant.owner', 'store.manager')
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

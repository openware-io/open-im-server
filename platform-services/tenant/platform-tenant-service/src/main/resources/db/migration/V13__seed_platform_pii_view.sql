-- 脱敏权限（member.pii.view）：平台运营最高权限也应可见会员隐私明文（跨租户监管）
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.code = 'member.pii.view'
WHERE r.code = 'platform.operator'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

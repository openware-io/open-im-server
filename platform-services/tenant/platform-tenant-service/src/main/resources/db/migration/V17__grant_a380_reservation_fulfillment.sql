-- A380 KTV 预约履约：租户管理员需要查看、确认、到店及开台权限。
-- 使用已有 ktv.session.operate 作为开台权限，避免引入未被 IAM 基线定义的孤立权限码。
INSERT INTO iam_permission
  (code, module, resource, action, description, status, created_by, created_at, updated_by, updated_at)
VALUES
  ('reservation.view', 'reservation', 'reservation', 'view', '查看预约', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('reservation.confirm', 'reservation', 'reservation', 'confirm', '确认预约', 'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('reservation.arrival', 'reservation', 'reservation', 'arrival', '预约到店', 'ACTIVE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE
  status = 'ACTIVE', updated_at = NOW(3);

INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.code IN ('reservation.view', 'reservation.confirm', 'reservation.arrival')
WHERE r.code = 'tenant.owner'
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

-- The order API uses order.add_item; seed and grant that explicit operation to A380 tenant owners.
INSERT INTO iam_permission
  (code, module, resource, action, description, status, created_by, created_at, updated_by, updated_at)
VALUES
  ('order.add_item', 'order', 'order', 'add_item', '订单加项', 'ACTIVE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = NOW(3);

INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.code = 'order.add_item'
WHERE r.code = 'tenant.owner'
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

-- A380 KTV 账单闭环：租户管理员需要对履约中的订单添加已定价项目。
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.code = 'order.add_item'
WHERE r.code = 'tenant.owner'
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

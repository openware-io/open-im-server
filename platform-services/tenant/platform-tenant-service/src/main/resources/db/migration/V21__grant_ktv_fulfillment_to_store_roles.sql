-- A380 KTV 履约闭环：门店角色（店长/收银员）需要预约查看/确认/到店以及订单加项权限。
-- V17/V18 只把 reservation.view/confirm/arrival 与 order.add_item 授予了 tenant.owner，
-- 导致店长/收银员进入 A380 商户端「预约」时被网关后的 order-service 拒绝：
--   403 PERMISSION_DENIED 缺少权限: reservation.view
-- 这里把四项权限补齐给门店角色；tenant.owner 已在 V17/V18 授予，重复执行幂等。
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p
  ON p.code IN ('reservation.view', 'reservation.confirm', 'reservation.arrival', 'order.add_item')
WHERE r.code IN ('store.manager', 'store.cashier')
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

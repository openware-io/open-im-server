-- A380 KTV 业务闭环：补齐代码 PermissionGuard 要求、但 iam_permission 中缺失的权限码，
-- 并按门店职责授予预置角色。缺失时任何角色都不持有该权限，接口直接 403：
--   POST /business/orders                     -> ktv.session.open
--   GET  /business/orders/{id}/items          -> order.view
--   POST /business/orders/{id}/servers/{}/end -> ktv.server.end
--   POST /business/reservations               -> reservation.create
--   POST /business/reservations/{id}/cancel   -> reservation.cancel
--   resource 服务资源管理/占用                 -> resource.manage / resource.occupy
INSERT INTO iam_permission
  (code, module, resource, action, description, status, created_by, created_at, updated_by, updated_at)
VALUES
  ('ktv.session.open',    'ktv',        'session',     'open',    '开台/开单',      'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('ktv.server.end',      'ktv',        'server',      'end',     '结束服务',        'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('order.view',          'order',      'order',       'view',    '查看订单与明细',  'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('reservation.create',  'reservation','reservation', 'create',  '创建预约',        'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('reservation.cancel',  'reservation','reservation', 'cancel',  '取消预约',        'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('resource.manage',     'resource',   'resource',    'manage',  '资源管理',        'ACTIVE', 0, NOW(3), 0, NOW(3)),
  ('resource.occupy',     'resource',   'resource',    'occupy',  '资源占用',        'ACTIVE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = NOW(3);

-- 租户管理员 + 店长：KTV 闭环全量权限
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p
  ON p.code IN ('ktv.session.open', 'ktv.server.end', 'order.view', 'reservation.create',
                'reservation.cancel', 'resource.manage', 'resource.occupy')
WHERE r.code IN ('tenant.owner', 'store.manager')
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

-- 收银员：开台/看单/结束服务（不含资源管理与取消预约）
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p
  ON p.code IN ('ktv.session.open', 'ktv.server.end', 'order.view', 'reservation.create', 'resource.occupy')
WHERE r.code = 'store.cashier'
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

-- 财务：只读订单
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.code = 'order.view'
WHERE r.code = 'store.finance'
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

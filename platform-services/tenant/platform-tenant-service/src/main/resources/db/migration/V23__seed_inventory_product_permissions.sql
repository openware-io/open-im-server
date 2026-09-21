-- KTV 仓库、商品上架及作废订单库存处理权限。
INSERT INTO iam_permission (code, module, resource, action, description, status, created_at, updated_at) VALUES
('inventory.material.view','inventory','material','view','查看仓库物料','ACTIVE',NOW(3),NOW(3)),
('inventory.material.manage','inventory','material','manage','维护仓库物料','ACTIVE',NOW(3),NOW(3)),
('inventory.receipt.create','inventory','receipt','create','物料入库','ACTIVE',NOW(3),NOW(3)),
('inventory.adjust','inventory','stock','adjust','库存调整出入库','ACTIVE',NOW(3),NOW(3)),
('inventory.transaction.view','inventory','transaction','view','查看库存流水','ACTIVE',NOW(3),NOW(3)),
('inventory.recovery.view','inventory','recovery','view','查看作废订单库存处理项','ACTIVE',NOW(3),NOW(3)),
('inventory.recovery.confirm','inventory','recovery','confirm','确认作废订单库存回补','ACTIVE',NOW(3),NOW(3)),
('product.view','product','product','view','查看商品','ACTIVE',NOW(3),NOW(3)),
('product.manage','product','product','manage','维护商品','ACTIVE',NOW(3),NOW(3)),
('product.publish','product','product','publish','商品上架','ACTIVE',NOW(3),NOW(3)),
('product.unpublish','product','product','unpublish','商品下架','ACTIVE',NOW(3),NOW(3))
ON DUPLICATE KEY UPDATE module=VALUES(module), resource=VALUES(resource), action=VALUES(action), updated_at=updated_at;

-- 租户老板、店长可完整管理库存和商品；老板/店长可处理作废订单回补。
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM iam_role r JOIN iam_permission p ON p.code IN (
  'inventory.material.view','inventory.material.manage','inventory.receipt.create','inventory.adjust',
  'inventory.transaction.view','inventory.recovery.view','inventory.recovery.confirm',
  'product.view','product.manage','product.publish','product.unpublish')
WHERE r.code IN ('tenant.owner','store.manager')
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);

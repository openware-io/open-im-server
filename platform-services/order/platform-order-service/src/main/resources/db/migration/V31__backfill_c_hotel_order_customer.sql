-- C 端酒店订单会员归属修复（一次性数据修复，幂等）。
--
-- 背景：C 端酒店下单在会员档案懒初始化之前成功时，ord_order.customer_id 为空；
-- htl_booking.booked_by_account_id 已有值，导致 /me/orders 按 customer_id 查不到订单。
-- 新写入路径已经在应用层要求 member 初始化；本迁移只修复历史行，不再回写客户表。
--
-- 数据归属：ord_order 由 order 域拥有；这里只读 cst_member 反查归属，不修改 customer 域。
UPDATE ord_order o
JOIN htl_booking b
  ON b.order_id = o.id
 AND b.tenant_id = o.tenant_id
JOIN cst_member m
  ON m.account_id = b.booked_by_account_id
 AND m.tenant_id = b.tenant_id
 AND m.status = 'ACTIVE'
SET o.customer_id = m.id,
    o.updated_at = NOW(3)
WHERE o.business_type = 'HOTEL'
  AND o.customer_id IS NULL
  AND b.booked_by_account_id IS NOT NULL;

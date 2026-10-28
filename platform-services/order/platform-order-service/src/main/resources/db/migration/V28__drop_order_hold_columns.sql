-- P2-3: remove order-level hold markers.
--
-- KTV 挂单/暂停已由 `ord_ktv_session.status = PAUSED` 表达（`KtvSessionApplicationService#pause/#resume`），
-- 订单主表不再保留 `hold_reason` / `hold_at`，运行时 `/orders/{id}/hold`、`/orders/{id}/unhold` 端点
-- 与权限 order.hold 直连的领域字段一并删除。clean rebuild 前该表为空，无需回填。
ALTER TABLE `ord_order`
  DROP COLUMN `hold_reason`,
  DROP COLUMN `hold_at`;

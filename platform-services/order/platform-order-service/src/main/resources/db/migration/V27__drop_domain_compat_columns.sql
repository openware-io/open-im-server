-- Final cleanup of the domain-boundary refactor (destructive).
--
-- Background: V24 added the target columns/tables additively so the repository stayed
-- compilable while KTV/Hotel/Inventory moved to the new model. All consumers now read
-- ord_order.trade_status / htl_room three-state columns / htl_room_availability(+hold),
-- so the legacy columns and the old "hotel inventory" tables are removed here.
--
-- Scope (and only this):
--   * ord_order.status + idx_ord_order_store_status  -> use trade_status / refund_status
--   * htl_room.status                                -> occupancy/housekeeping/out_of_order
--   * htl_inventory_calendar / htl_inventory_hold    -> htl_room_availability(+hold); clean rebuild drops the legacy tables (never populated in the new model)
--
-- hold_reason/hold_at are intentionally kept: pending holds are still a KTV order-level
-- business marker until the hold state moves onto ord_ktv_session (separate scope).

ALTER TABLE `ord_order`
  DROP INDEX `idx_ord_order_store_status`,
  DROP COLUMN `status`;

ALTER TABLE `htl_room`
  DROP COLUMN `status`;

DROP TABLE IF EXISTS `htl_inventory_hold`;
DROP TABLE IF EXISTS `htl_inventory_calendar`;

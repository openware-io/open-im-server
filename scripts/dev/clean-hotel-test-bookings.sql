-- 仅用于本地/测试环境的酒店脏数据清理，不进入生产迁移。
-- 问号住客名是历史测试客户端以非 UTF-8 写入后被 MySQL 替换成 0x3F，不是前端显示问题。
CREATE TEMPORARY TABLE tmp_hotel_test_booking_ids (id BIGINT PRIMARY KEY);
INSERT INTO tmp_hotel_test_booking_ids (id)
SELECT id FROM htl_booking
WHERE (guest_name REGEXP '^[?]+$' OR guest_name = 'DeviceWebView')
  AND status IN ('CANCELLED', 'NO_SHOW', 'CHECKED_OUT');

DELETE FROM htl_housekeeping_task WHERE booking_id IN (SELECT id FROM tmp_hotel_test_booking_ids);
DELETE FROM htl_folio_item WHERE folio_id IN (
  SELECT id FROM htl_folio WHERE booking_id IN (SELECT id FROM tmp_hotel_test_booking_ids)
);
DELETE FROM htl_folio WHERE booking_id IN (SELECT id FROM tmp_hotel_test_booking_ids);
DELETE FROM htl_stay WHERE booking_id IN (SELECT id FROM tmp_hotel_test_booking_ids);
DELETE FROM htl_inventory_hold WHERE booking_id IN (SELECT id FROM tmp_hotel_test_booking_ids);
DELETE FROM htl_booking_night WHERE booking_id IN (SELECT id FROM tmp_hotel_test_booking_ids);
DELETE FROM htl_booking WHERE id IN (SELECT id FROM tmp_hotel_test_booking_ids);
DROP TEMPORARY TABLE tmp_hotel_test_booking_ids;

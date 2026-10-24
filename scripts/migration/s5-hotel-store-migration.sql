-- S5 酒店域从 KTV 门店迁到 HOTEL 门店（幂等，可回滚）。
-- 用法：在目标库执行；先备份 htl_* 与 tnt_store。
-- 回滚：把 htl_* 的 store_id 改回原门店，并删除 code='a380-hotel-001' 的 tnt_store 行。

START TRANSACTION;

INSERT INTO `tnt_store`
  (`tenant_id`, `organization_id`, `code`, `name`, `business_type`, `country_code`,
   `timezone`, `default_currency`, `locale`, `status`, `created_by`, `created_at`,
   `updated_by`, `updated_at`)
SELECT 100, 100, 'a380-hotel-001', 'A380 Hotel', 'HOTEL', 'CN',
       'Asia/Shanghai', 'CNY', 'zh-CN', 'ACTIVE', 0, NOW(3), 0, NOW(3)
WHERE NOT EXISTS (
  SELECT 1 FROM `tnt_store` WHERE `tenant_id` = 100 AND `code` = 'a380-hotel-001'
);

SET @hotel_store_id = (
  SELECT `id` FROM `tnt_store`
  WHERE `tenant_id` = 100 AND `code` = 'a380-hotel-001'
  LIMIT 1
);

UPDATE `htl_hotel` SET `store_id` = @hotel_store_id WHERE `tenant_id` = 100 AND `store_id` = 100;
UPDATE `htl_room_type` SET `store_id` = @hotel_store_id WHERE `tenant_id` = 100 AND `store_id` = 100;
UPDATE `htl_rate_plan` SET `store_id` = @hotel_store_id WHERE `tenant_id` = 100 AND `store_id` = 100;
UPDATE `htl_room` SET `store_id` = @hotel_store_id WHERE `tenant_id` = 100 AND `store_id` = 100;
UPDATE `htl_inventory_calendar` SET `store_id` = @hotel_store_id WHERE `tenant_id` = 100 AND `store_id` = 100;
UPDATE `htl_booking` SET `store_id` = @hotel_store_id WHERE `tenant_id` = 100 AND `store_id` = 100;
UPDATE `htl_booking_night` SET `store_id` = @hotel_store_id WHERE `tenant_id` = 100 AND `store_id` = 100;
UPDATE `htl_stay` SET `store_id` = @hotel_store_id WHERE `tenant_id` = 100 AND `store_id` = 100;
UPDATE `htl_folio` SET `store_id` = @hotel_store_id WHERE `tenant_id` = 100 AND `store_id` = 100;
UPDATE `htl_folio_item` SET `store_id` = @hotel_store_id WHERE `tenant_id` = 100 AND `store_id` = 100;
UPDATE `htl_refund` SET `store_id` = @hotel_store_id WHERE `tenant_id` = 100 AND `store_id` = 100;
UPDATE `htl_housekeeping_task` SET `store_id` = @hotel_store_id WHERE `tenant_id` = 100 AND `store_id` = 100;
UPDATE `htl_night_audit` SET `store_id` = @hotel_store_id WHERE `tenant_id` = 100 AND `store_id` = 100;
UPDATE `htl_inventory_hold` SET `store_id` = @hotel_store_id WHERE `tenant_id` = 100 AND `store_id` = 100;

COMMIT;

-- 校验：HOTEL 门店下酒店域行数；KTV 门店 100 不应再有酒店域行。
SELECT @hotel_store_id AS hotel_store_id,
       (SELECT COUNT(*) FROM `htl_hotel` WHERE `tenant_id` = 100 AND `store_id` = @hotel_store_id) AS hotel_rows,
       (SELECT COUNT(*) FROM `htl_booking` WHERE `tenant_id` = 100 AND `store_id` = 100) AS ktv_booking_rows;

-- P2-3: KTV reservation tables take the dedicated `ktv_` prefix.
--
-- `ord_reservation` was only ever used by KTV (business_type = KTV; hotel bookings live in
-- `htl_booking`), so the table is renamed to `ktv_reservation` and the rule config table to
-- `ktv_reservation_rule_config`. No data change; indexes are renamed to keep the
-- `uk_<table>_<semantic>` / `idx_<table>_<semantic>` convention. The old CHECK constraint name
-- (`ck_ord_reservation_party_size`) is left untouched: MySQL cannot rename a CHECK constraint in
-- place and it carries no runtime meaning.
RENAME TABLE `ord_reservation` TO `ktv_reservation`,
             `ord_reservation_rule_config` TO `ktv_reservation_rule_config`;

ALTER TABLE `ktv_reservation`
  RENAME INDEX `uk_ord_reservation_tenant_no` TO `uk_ktv_reservation_tenant_no`,
  RENAME INDEX `uk_ord_reservation_idem` TO `uk_ktv_reservation_idem`,
  RENAME INDEX `idx_ord_reservation_store_status` TO `idx_ktv_reservation_store_status`,
  RENAME INDEX `idx_ord_reservation_customer` TO `idx_ktv_reservation_customer`,
  RENAME INDEX `idx_ord_reservation_resource` TO `idx_ktv_reservation_resource`,
  RENAME INDEX `idx_ord_reservation_room_type` TO `idx_ktv_reservation_room_type`;

ALTER TABLE `ktv_reservation_rule_config`
  RENAME INDEX `uk_ord_reservation_rule_config_scope` TO `uk_ktv_reservation_rule_config_scope`,
  RENAME INDEX `uk_ord_reservation_rule_config_idem` TO `uk_ktv_reservation_rule_config_idem`,
  RENAME INDEX `idx_ord_reservation_rule_config_resolve` TO `idx_ktv_reservation_rule_config_resolve`;

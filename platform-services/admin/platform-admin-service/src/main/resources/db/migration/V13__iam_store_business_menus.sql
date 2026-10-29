-- V13__iam_store_business_menus.sql
-- Domain: admin console menu projection. Store managers and tenant headquarters
-- need explicit business-specific entries; business_type prunes each context.

INSERT INTO `iam_menu`
  (`parent_id`, `code`, `name`, `path`, `icon`, `scope_level`, `domain_code`, `required_permission`,
   `required_grant`, `business_type`, `i18n_key`, `sort_no`, `status`, `created_by`, `created_at`,
   `updated_by`, `updated_at`)
SELECT 103, 'tenant.hotel', '酒店管理', '/admin/tenant/hotel', 'hotel', 'TENANT', 'hotel',
       'hotel.booking.view', NULL, 'HOTEL', NULL, 10, 'ACTIVE', 0, NOW(3), 0, NOW(3)
WHERE NOT EXISTS (
  SELECT 1 FROM `iam_menu` WHERE `scope_level` = 'TENANT' AND `code` = 'tenant.hotel'
);

INSERT INTO `iam_menu`
  (`parent_id`, `code`, `name`, `path`, `icon`, `scope_level`, `domain_code`, `required_permission`,
   `required_grant`, `business_type`, `i18n_key`, `sort_no`, `status`, `created_by`, `created_at`,
   `updated_by`, `updated_at`)
SELECT 200, 'store.hotel', '酒店前台', '/admin/tenant/hotel', 'hotel', 'STORE', 'hotel',
       'hotel.booking.view', NULL, 'HOTEL', NULL, 1, 'ACTIVE', 0, NOW(3), 0, NOW(3)
WHERE NOT EXISTS (
  SELECT 1 FROM `iam_menu` WHERE `scope_level` = 'STORE' AND `code` = 'store.hotel'
);

INSERT INTO `iam_menu`
  (`parent_id`, `code`, `name`, `path`, `icon`, `scope_level`, `domain_code`, `required_permission`,
   `required_grant`, `business_type`, `i18n_key`, `sort_no`, `status`, `created_by`, `created_at`,
   `updated_by`, `updated_at`)
SELECT 200, 'store.ktv', 'KTV 配置', '/admin/ktv/config', 'setting', 'STORE', 'ktv',
       'ktv.config.manage', NULL, 'KTV', NULL, 2, 'ACTIVE', 0, NOW(3), 0, NOW(3)
WHERE NOT EXISTS (
  SELECT 1 FROM `iam_menu` WHERE `scope_level` = 'STORE' AND `code` = 'store.ktv'
);

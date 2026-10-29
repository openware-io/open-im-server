-- V12__iam_platform_operator_menu.sql
-- Domain: admin console menu projection. Platform operator accounts and their
-- IAM platform-role bindings are managed on a dedicated platform page.

INSERT INTO `iam_menu`
  (`parent_id`, `code`, `name`, `path`, `icon`, `scope_level`, `domain_code`, `required_permission`,
   `required_grant`, `business_type`, `i18n_key`, `sort_no`, `status`, `created_by`, `created_at`,
   `updated_by`, `updated_at`)
SELECT 3, 'iam.platform.operator', '平台运营人员', '/admin/iam/platform-operators', 'service', 'PLATFORM', 'core',
       'platform.iam.role.view', NULL, NULL, NULL, 30, 'ACTIVE', 0, NOW(3), 0, NOW(3)
WHERE NOT EXISTS (
  SELECT 1 FROM `iam_menu` WHERE `scope_level` = 'PLATFORM' AND `code` = 'iam.platform.operator'
);

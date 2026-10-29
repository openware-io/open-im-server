-- V10__iam_role_menu_view_permission.sql
-- Domain: admin console menu projection. Role pages are now readable with the
-- dedicated view permission; write actions remain gated by iam.role.manage.

UPDATE `iam_menu`
SET `required_permission` = 'iam.role.view',
    `updated_at` = NOW(3)
WHERE `scope_level` = 'TENANT'
  AND `code` = 'iam.role';

UPDATE `iam_menu`
SET `required_permission` = 'platform.iam.role.view',
    `updated_at` = NOW(3)
WHERE `scope_level` = 'PLATFORM'
  AND `code` IN ('iam.role', 'iam.permission');

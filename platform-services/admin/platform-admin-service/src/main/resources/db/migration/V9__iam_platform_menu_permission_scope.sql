-- V9__iam_platform_menu_permission_scope.sql
-- Domain: admin console menu projection. Platform IAM pages are platform-admin
-- capabilities; use the platform-scoped permission, not the tenant role permission.

UPDATE `iam_menu`
SET `required_permission` = 'platform.iam.role.manage',
    `updated_at` = NOW(3)
WHERE `scope_level` = 'PLATFORM'
  AND `code` IN ('iam.role', 'iam.permission');

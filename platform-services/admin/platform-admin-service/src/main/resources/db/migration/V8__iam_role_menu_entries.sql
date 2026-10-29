-- V8__iam_role_menu_entries.sql
-- Domain: admin console menu projection. Adds the tenant role-management entry and
-- aligns platform IAM menu labels/permission gates with the role page.

-- Tenant HQ: the role page route already exists (/admin/roles) but had no menu row.
INSERT INTO `iam_menu`
  (`parent_id`, `code`, `name`, `path`, `icon`, `scope_level`, `domain_code`, `required_permission`,
   `required_grant`, `business_type`, `i18n_key`, `sort_no`, `status`, `created_by`, `created_at`,
   `updated_by`, `updated_at`)
SELECT 102, 'iam.role', '角色与权限', '/admin/roles', 'avatar', 'TENANT', 'core', 'iam.role.manage',
       NULL, NULL, NULL, 5, 'ACTIVE', 0, NOW(3), 0, NOW(3)
WHERE NOT EXISTS (
  SELECT 1 FROM `iam_menu` WHERE `scope_level` = 'TENANT' AND `code` = 'iam.role'
);

-- Platform: the role page title is "角色与权限"; the permission page title is "权限目录".
UPDATE `iam_menu`
SET `name` = '角色与权限',
    `updated_at` = NOW(3)
WHERE `scope_level` = 'PLATFORM'
  AND `code` = 'iam.role';

UPDATE `iam_menu`
SET `name` = '权限目录',
    `updated_at` = NOW(3)
WHERE `scope_level` = 'PLATFORM'
  AND `code` = 'iam.permission';

-- Both platform IAM entries are management pages; gate the menu with the same
-- permission used by their write operations. Direct API access is guarded separately.
UPDATE `iam_menu`
SET `required_permission` = 'iam.role.manage',
    `updated_at` = NOW(3)
WHERE `scope_level` = 'PLATFORM'
  AND `code` IN ('iam.role', 'iam.permission');

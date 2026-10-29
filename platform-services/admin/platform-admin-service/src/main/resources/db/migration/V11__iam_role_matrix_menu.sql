-- V11__iam_role_matrix_menu.sql
-- Domain: admin console menu projection. Adds the tenant role-permission matrix entry,
-- visible to role viewers and writable only through the role page.

INSERT INTO `iam_menu`
  (`parent_id`, `code`, `name`, `path`, `icon`, `scope_level`, `domain_code`, `required_permission`,
   `required_grant`, `business_type`, `i18n_key`, `sort_no`, `status`, `created_by`, `created_at`,
   `updated_by`, `updated_at`)
SELECT 102, 'iam.role.matrix', '角色权限矩阵', '/admin/roles/matrix', 'grid', 'TENANT', 'core',
       'iam.role.view', NULL, NULL, NULL, 6, 'ACTIVE', 0, NOW(3), 0, NOW(3)
WHERE NOT EXISTS (
  SELECT 1 FROM `iam_menu` WHERE `scope_level` = 'TENANT' AND `code` = 'iam.role.matrix'
);

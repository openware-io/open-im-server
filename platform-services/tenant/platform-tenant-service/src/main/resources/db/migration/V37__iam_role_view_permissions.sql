-- V37__iam_role_view_permissions.sql
-- Domain: IAM. Split role read permission from role write permission so a
-- read-only role viewer can open the role page without receiving iam.role.manage.

INSERT INTO `iam_permission`
  (`code`, `module`, `resource`, `action`, `description`, `status`, `scope_level`, `domain_code`,
   `grantable_levels`, `menu_code`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('iam.role.view', 'iam', 'role', 'view', '角色查看', 'ACTIVE', 'TENANT', 'core',
   'TENANT,PLATFORM', 'iam.role', 0, NOW(3), 0, NOW(3)),
  ('platform.iam.role.view', 'platform', 'iam.role', 'view', '平台角色查看', 'ACTIVE', 'PLATFORM',
   'core', 'PLATFORM', NULL, 0, NOW(3), 0, NOW(3));

-- Existing role managers imply read access; add the new view grants so they keep
-- their current page/menu access after the menu gate moves from manage to view.
INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT `r`.`id`, `p`.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` `r`
JOIN `iam_permission` `p` ON `p`.`code` = 'iam.role.view'
WHERE `r`.`status` = 'ACTIVE'
  AND `r`.`tenant_id` IS NOT NULL
  AND FIND_IN_SET(`r`.`scope_level`, `p`.`grantable_levels`) > 0
  AND EXISTS (
    SELECT 1 FROM `iam_role_permission` `rp`
    JOIN `iam_permission` `manage` ON `manage`.`id` = `rp`.`permission_id`
    WHERE `rp`.`role_id` = `r`.`id` AND `manage`.`code` = 'iam.role.manage'
  )
  AND NOT EXISTS (
    SELECT 1 FROM `iam_role_permission` `rp`
    WHERE `rp`.`role_id` = `r`.`id` AND `rp`.`permission_id` = `p`.`id`
  );

INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT `r`.`id`, `p`.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` `r`
JOIN `iam_permission` `p` ON `p`.`code` = 'platform.iam.role.view'
WHERE `r`.`status` = 'ACTIVE'
  AND `r`.`tenant_id` IS NULL
  AND FIND_IN_SET(`r`.`scope_level`, `p`.`grantable_levels`) > 0
  AND EXISTS (
    SELECT 1 FROM `iam_role_permission` `rp`
    JOIN `iam_permission` `manage` ON `manage`.`id` = `rp`.`permission_id`
    WHERE `rp`.`role_id` = `r`.`id` AND `manage`.`code` = 'platform.iam.role.manage'
  )
  AND NOT EXISTS (
    SELECT 1 FROM `iam_role_permission` `rp`
    WHERE `rp`.`role_id` = `r`.`id` AND `rp`.`permission_id` = `p`.`id`
  );

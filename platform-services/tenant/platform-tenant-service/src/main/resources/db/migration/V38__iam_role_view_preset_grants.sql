-- V38__iam_role_view_preset_grants.sql
-- Domain: IAM. Tenant preset roles have tenant_id NULL (they are global role
-- definitions), so V37 only covered tenant-custom roles. Grant the new read
-- permission to global tenant-scope presets that already hold iam.role.manage.

INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT `r`.`id`, `p`.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` `r`
JOIN `iam_permission` `p` ON `p`.`code` = 'iam.role.view'
WHERE `r`.`status` = 'ACTIVE'
  AND `r`.`tenant_id` IS NULL
  AND `r`.`scope_level` = 'TENANT'
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

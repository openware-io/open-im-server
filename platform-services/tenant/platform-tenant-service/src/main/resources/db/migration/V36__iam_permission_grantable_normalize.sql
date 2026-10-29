-- V36__iam_permission_grantable_normalize.sql
-- Domain: IAM. Normalize preset-role baseline grants so every role-permission grant
-- stays inside the permission's grantable_levels. This is a data correction, not a
-- schema change: preset roles were seeded with business permissions whose
-- grantable_levels only listed narrower scopes.

-- Platform operator is a platform-scope role that operates tenant/store contexts;
-- its business grants must be grantable to PLATFORM.
UPDATE `iam_permission` `p`
JOIN `iam_role_permission` `rp` ON `rp`.`permission_id` = `p`.`id`
JOIN `iam_role` `r` ON `r`.`id` = `rp`.`role_id`
SET `p`.`grantable_levels` = CONCAT(`p`.`grantable_levels`, ',PLATFORM'),
    `p`.`updated_by` = 0,
    `p`.`updated_at` = NOW(3)
WHERE `r`.`code` = 'platform.operator'
  AND `r`.`tenant_id` IS NULL
  AND `r`.`status` = 'ACTIVE'
  AND `p`.`status` = 'ACTIVE'
  AND NOT FIND_IN_SET('PLATFORM', `p`.`grantable_levels`);

-- Tenant owner is a tenant-scope role that owns all business capabilities in the
-- tenant; store-scoped permissions it holds must also be grantable to TENANT.
UPDATE `iam_permission` `p`
JOIN `iam_role_permission` `rp` ON `rp`.`permission_id` = `p`.`id`
JOIN `iam_role` `r` ON `r`.`id` = `rp`.`role_id`
SET `p`.`grantable_levels` = CONCAT(`p`.`grantable_levels`, ',TENANT'),
    `p`.`updated_by` = 0,
    `p`.`updated_at` = NOW(3)
WHERE `r`.`code` = 'tenant.owner'
  AND `r`.`tenant_id` IS NULL
  AND `r`.`status` = 'ACTIVE'
  AND `p`.`status` = 'ACTIVE'
  AND NOT FIND_IN_SET('TENANT', `p`.`grantable_levels`);

-- Store manager manages the store catalogue/operations, so the store-scope grant
-- it holds must be grantable to STORE. (Only permissions already granted to the
-- preset role are touched; no new grants are created.)
UPDATE `iam_permission` `p`
JOIN `iam_role_permission` `rp` ON `rp`.`permission_id` = `p`.`id`
JOIN `iam_role` `r` ON `r`.`id` = `rp`.`role_id`
SET `p`.`grantable_levels` = CONCAT(`p`.`grantable_levels`, ',STORE'),
    `p`.`updated_by` = 0,
    `p`.`updated_at` = NOW(3)
WHERE `r`.`code` = 'store.manager'
  AND `r`.`tenant_id` IS NULL
  AND `r`.`status` = 'ACTIVE'
  AND `p`.`status` = 'ACTIVE'
  AND NOT FIND_IN_SET('STORE', `p`.`grantable_levels`);

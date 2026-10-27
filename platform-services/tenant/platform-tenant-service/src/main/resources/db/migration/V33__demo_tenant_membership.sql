-- Demo tenant membership and operating-context bindings for a clean Kind DB.
-- Context selection joins tnt_membership, so an ACCOUNT binding alone cannot
-- produce KTV/HOTEL store contexts. The tenant owner binding is organization
-- scoped and expands to both stores; the store-manager binding makes the hotel
-- store explicitly operable during E2E.
INSERT INTO `tnt_membership`
  (`account_id`, `tenant_id`, `organization_id`, `membership_type`, `member_no`,
   `authorization_version`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT 100, 100, 100, 'STAFF', 'DEMO-100', 1, 'ACTIVE', 0, NOW(3), 0, NOW(3)
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM `tnt_membership`
  WHERE `account_id` = 100 AND `tenant_id` = 100 AND `membership_type` = 'STAFF'
);

INSERT INTO `iam_role_binding`
  (`subject_type`, `subject_id`, `role_id`, `scope_type`, `tenant_id`, `organization_id`, `store_id`,
   `authorization_version`, `status`, `effective_from`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT 'MEMBERSHIP', m.`id`, r.`id`, 'ORGANIZATION', 100, 100, 0, 1, 'ACTIVE', NOW(3), 0, NOW(3), 0, NOW(3)
FROM `tnt_membership` m
JOIN `iam_role` r ON r.`code` = 'tenant.owner' AND r.`status` = 'ACTIVE'
WHERE m.`account_id` = 100 AND m.`tenant_id` = 100 AND m.`membership_type` = 'STAFF'
  AND NOT EXISTS (
    SELECT 1 FROM `iam_role_binding` rb
    WHERE rb.`subject_type` = 'MEMBERSHIP' AND rb.`subject_id` = m.`id`
      AND rb.`role_id` = r.`id` AND rb.`scope_type` = 'ORGANIZATION'
      AND rb.`tenant_id` = 100 AND rb.`organization_id` = 100
  );

INSERT INTO `iam_role_binding`
  (`subject_type`, `subject_id`, `role_id`, `scope_type`, `tenant_id`, `organization_id`, `store_id`,
   `authorization_version`, `status`, `effective_from`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT 'MEMBERSHIP', m.`id`, r.`id`, 'STORE', 100, 100, 102, 1, 'ACTIVE', NOW(3), 0, NOW(3), 0, NOW(3)
FROM `tnt_membership` m
JOIN `iam_role` r ON r.`code` = 'store.manager' AND r.`status` = 'ACTIVE'
WHERE m.`account_id` = 100 AND m.`tenant_id` = 100 AND m.`membership_type` = 'STAFF'
  AND NOT EXISTS (
    SELECT 1 FROM `iam_role_binding` rb
    WHERE rb.`subject_type` = 'MEMBERSHIP' AND rb.`subject_id` = m.`id`
      AND rb.`role_id` = r.`id` AND rb.`scope_type` = 'STORE'
      AND rb.`tenant_id` = 100 AND rb.`store_id` = 102
  );

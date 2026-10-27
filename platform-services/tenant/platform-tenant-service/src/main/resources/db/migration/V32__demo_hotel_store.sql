-- Demo hotel store for a clean Kind rebuild.
-- The clean database previously only had the KTV store; hotel E2E needs a
-- real HOTEL store under the same tenant/organization.
INSERT INTO `tnt_store`
  (`id`, `tenant_id`, `organization_id`, `code`, `name`, `business_type`, `country_code`, `timezone`, `default_currency`, `locale`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  (102, 100, 100, 'S929-hotel-001', 'SaaS Hotel', 'HOTEL', 'CN', 'Asia/Shanghai', 'CNY', 'zh-CN', 'ACTIVE', 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE
  `name` = VALUES(`name`), `organization_id` = VALUES(`organization_id`),
  `business_type` = VALUES(`business_type`), `status` = 'ACTIVE', `updated_at` = NOW(3);

INSERT INTO `tnt_business_entitlement` (`tenant_id`, `business_type`, `status`, `created_at`, `updated_at`)
VALUES (100, 'HOTEL', 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE `status` = 'ACTIVE', `updated_at` = NOW(3);

INSERT INTO `iam_role_binding`
  (`subject_type`, `subject_id`, `role_id`, `scope_type`, `tenant_id`, `organization_id`, `store_id`,
   `authorization_version`, `status`, `effective_from`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT 'ACCOUNT', 100, r.`id`, 'STORE', 100, 100, 102, 1, 'ACTIVE', NOW(3), 0, NOW(3), 0, NOW(3)
FROM `iam_role` r
WHERE r.`code` = 'store.manager'
  AND NOT EXISTS (
    SELECT 1 FROM `iam_role_binding` rb
    WHERE rb.`subject_type` = 'ACCOUNT' AND rb.`subject_id` = 100
      AND rb.`tenant_id` = 100 AND rb.`store_id` = 102
      AND rb.`role_id` = r.`id` AND rb.`status` = 'ACTIVE'
  );

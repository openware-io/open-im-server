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

INSERT INTO `iam_user_role`
  (`account_id`, `tenant_id`, `organization_id`, `store_id`, `role_id`, `scope_type`,
   `effective_from`, `status`, `authorization_version`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT 100, 100, 100, 102, r.`id`, 'STORE', NOW(3), 'ACTIVE', 1, 0, NOW(3), 0, NOW(3)
FROM `iam_role` r
WHERE r.`code` = 'store.manager'
  AND NOT EXISTS (
    SELECT 1 FROM `iam_user_role` ur
    WHERE ur.`account_id` = 100 AND ur.`tenant_id` = 100 AND ur.`store_id` = 102
      AND ur.`role_id` = r.`id` AND ur.`status` = 'ACTIVE'
  );

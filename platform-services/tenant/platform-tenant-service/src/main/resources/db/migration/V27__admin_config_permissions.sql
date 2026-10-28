-- 后台配置类接口权限：KTV 配置、图片上传不能只靠“登录后台”就放行。
INSERT INTO `iam_permission`
  (`code`, `module`, `resource`, `action`, `description`, `status`, `scope_level`, `domain_code`, `grantable_levels`, `menu_code`, `created_by`, `created_at`, `updated_by`, `updated_at`)
VALUES
  ('ktv.config.manage', 'ktv', 'config', 'manage', '维护 KTV 价格、支付、预约与作废规则', 'ACTIVE', 'TENANT', 'ktv', 'TENANT,STORE', NULL, 0, NOW(3), 0, NOW(3)),
  ('media.image.upload', 'media', 'image', 'upload', '后台上传业务图片', 'ACTIVE', 'TENANT', 'core', 'TENANT,STORE', NULL, 0, NOW(3), 0, NOW(3))
ON DUPLICATE KEY UPDATE
  `description` = VALUES(`description`),
  `scope_level` = VALUES(`scope_level`),
  `domain_code` = VALUES(`domain_code`),
  `grantable_levels` = VALUES(`grantable_levels`),
  `updated_at` = VALUES(`updated_at`);

INSERT INTO `iam_role_permission` (`role_id`, `permission_id`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT r.`id`, p.`id`, 0, NOW(3), 0, NOW(3)
FROM `iam_role` r JOIN `iam_permission` p ON p.`code` IN ('ktv.config.manage', 'media.image.upload')
WHERE r.`code` IN ('tenant.owner', 'store.manager', 'store.supervisor')
ON DUPLICATE KEY UPDATE `updated_at` = VALUES(`updated_at`);

UPDATE `iam_menu`
SET `required_permission` = 'ktv.config.manage', `updated_at` = NOW(3)
WHERE `code` IN ('ktv', 'pricing');

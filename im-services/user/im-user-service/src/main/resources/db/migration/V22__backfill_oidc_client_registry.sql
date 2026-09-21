-- Environment deployment seeds own redirect URIs; this migration only backfills structured client facts.
INSERT INTO `user_oidc_client`
  (`client_id`, `client_name`, `client_type`, `token_endpoint_auth_method`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT `app_id`, `app_name`,
       CASE WHEN `app_type` = 'PUBLIC' THEN 'PUBLIC' ELSE 'CONFIDENTIAL' END,
       CASE WHEN `app_type` = 'PUBLIC' THEN 'none' ELSE 'client_secret_basic' END,
       CASE WHEN `status` = 'APPROVED' THEN 'ACTIVE' ELSE 'REVOKED' END,
       0, CURRENT_TIMESTAMP(3), 0, CURRENT_TIMESTAMP(3)
FROM `open_application`
WHERE `app_id` IN ('saas-ktv', 'saas-a380-c', 'saas-a380-h5')
ON DUPLICATE KEY UPDATE
  `client_name` = VALUES(`client_name`), `client_type` = VALUES(`client_type`),
  `token_endpoint_auth_method` = VALUES(`token_endpoint_auth_method`),
  `status` = VALUES(`status`), `updated_at` = VALUES(`updated_at`);

INSERT INTO `user_oidc_redirect_uri` (`client_id`, `redirect_uri`)
SELECT `app_id`, `callback_url`
FROM `open_application`
WHERE `app_id` IN ('saas-ktv', 'saas-a380-c', 'saas-a380-h5')
ON DUPLICATE KEY UPDATE `redirect_uri` = VALUES(`redirect_uri`);

INSERT INTO `user_oidc_scope` (`client_id`, `scope`)
SELECT a.`app_id`, s.`scope`
FROM `open_application` a
JOIN `open_application_scope` s ON s.`application_id` = a.`id`
WHERE a.`app_id` IN ('saas-ktv', 'saas-a380-c', 'saas-a380-h5')
ON DUPLICATE KEY UPDATE `scope` = VALUES(`scope`);

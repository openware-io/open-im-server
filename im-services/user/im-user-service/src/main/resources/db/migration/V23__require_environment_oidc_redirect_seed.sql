-- Callback URLs are environment facts. Do not carry a historical environment into a new deployment.
DELETE FROM `user_oidc_redirect_uri`
WHERE `client_id` IN ('saas-a380-c', 'saas-a380-h5');

UPDATE `open_application`
SET `callback_url` = CONCAT('https://unconfigured.invalid/oidc/', `app_id`)
WHERE `app_id` IN ('saas-a380-c', 'saas-a380-h5');

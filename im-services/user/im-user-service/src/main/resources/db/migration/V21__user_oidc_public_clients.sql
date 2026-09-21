-- Mobile/Desktop public clients authenticate with PKCE and never carry a secret.
UPDATE `open_application`
   SET `app_type` = 'PUBLIC', `app_secret_hash` = ''
 WHERE `app_id` IN ('saas-ktv', 'saas-a380-c', 'saas-a380-h5');

INSERT INTO `open_application_scope` (`application_id`, `scope`)
SELECT `id`, 'openid' FROM `open_application` a
 WHERE a.`app_id` IN ('saas-ktv', 'saas-a380-c', 'saas-a380-h5')
   AND NOT EXISTS (SELECT 1 FROM `open_application_scope` s
                    WHERE s.`application_id` = a.`id` AND s.`scope` = 'openid');

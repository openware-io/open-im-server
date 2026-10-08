UPDATE `open_application`
SET `callback_url` = 'openchat://oauth/callback',
    `updated_at` = NOW(3)
WHERE `app_id` = 'saas-ktv'
  AND `callback_url` = 'gvchat://oauth/callback';

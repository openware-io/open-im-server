-- A380 H5 OAuth clients：C 端与 B 端使用独立 appId 和精确回调地址。
-- 仅保存 appSecret SHA-256 哈希；两个公开客户端均采用授权码 + PKCE。
INSERT INTO `open_application`
  (`app_id`, `app_name`, `app_type`, `callback_url`, `app_secret_hash`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT 'saas-a380-c', 'A380 更多服务', 'THIRD_PARTY',
       'https://miniservice.dev.example.com/a380/',
       '353f718b81b455321e8f7a3e16c3282f5c74c2474ad5f6502e7d8b4a849f8eb5',
       'APPROVED', 0, CURRENT_TIMESTAMP(3), 0, CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
  SELECT 1 FROM `open_application` WHERE `app_id` = 'saas-a380-c'
);

INSERT INTO `open_application`
  (`app_id`, `app_name`, `app_type`, `callback_url`, `app_secret_hash`, `status`, `created_by`, `created_at`, `updated_by`, `updated_at`)
SELECT 'saas-a380-h5', 'A380 商户端', 'THIRD_PARTY',
       'https://miniservice.dev.example.com/b/',
       '353f718b81b455321e8f7a3e16c3282f5c74c2474ad5f6502e7d8b4a849f8eb5',
       'APPROVED', 0, CURRENT_TIMESTAMP(3), 0, CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
  SELECT 1 FROM `open_application` WHERE `app_id` = 'saas-a380-h5'
);

INSERT INTO `open_application_scope` (`application_id`, `scope`)
SELECT `id`, 'profile.basic'
FROM `open_application` a
WHERE a.`app_id` IN ('saas-a380-c', 'saas-a380-h5')
  AND NOT EXISTS (
    SELECT 1 FROM `open_application_scope` s
    WHERE s.`application_id` = a.`id` AND s.`scope` = 'profile.basic'
  );

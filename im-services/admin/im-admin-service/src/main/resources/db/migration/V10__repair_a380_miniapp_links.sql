-- 修复历史环境中仍指向统一门户的 A380 服务项，统一到 SaaS 移动服务域名。
UPDATE adm_miniapp_service_item
SET name = 'A380 更多服务',
    link = 'https://miniservice.dev.example.com/a380/',
    introduction = 'A380 更多服务（酒店/KTV/酒吧/足浴/美食/出行）',
    audience = 'consumer',
    is_top = 1,
    status = 1,
    updated_at = NOW(3)
WHERE name = 'A380 更多服务'
   OR link IN (
       'https://admin.dev.example.com/a380',
       'https://admin.dev.example.com/a380/',
       'https://miniservice.dev.example.com/a380',
       'https://www.miniservice.dev.example.com/a380'
   );

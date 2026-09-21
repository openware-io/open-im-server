-- C 端入口：把「KTV 预订」示例项升级为「A380 更多服务」，指向 A380 H5（consumer 固定展示）
UPDATE adm_miniapp_service_item
SET name = 'A380 更多服务',
    link = 'https://www.miniservice.dev.example.com/a380',
    introduction = 'A380 更多服务（酒店/KTV/酒吧/足浴/美食/出行）',
    audience = 'consumer',
    is_top = 1,
    status = 1,
    updated_at = NOW(3)
WHERE link = 'https://www.example.com/ktv';

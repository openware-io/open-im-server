UPDATE adm_miniapp_service_item
SET name = REPLACE(name, 'OpenChat', 'SaaS'),
    introduction = REPLACE(introduction, 'OpenChat', 'SaaS')
WHERE name LIKE 'OpenChat%' OR introduction LIKE 'OpenChat%';

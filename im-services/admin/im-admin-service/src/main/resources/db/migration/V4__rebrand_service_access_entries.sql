-- Rebrand the demo service catalog from the legacy A380 tenant name to the
-- open-source OpenChat identity. V3 is already applied and must not be edited;
-- this migration keeps the technical /a380/ route while changing only display
-- names and descriptions.
UPDATE `adm_miniapp_service_item`
SET `name` = 'OpenChat 服务中心',
    `introduction` = 'OpenChat 服务中心：酒店、KTV、酒吧、台球、足浴、美食与出行',
    `updated_at` = NOW(3)
WHERE `name` = 'A380 更多服务' AND `audience` = 'consumer';

UPDATE `adm_miniapp_service_item`
SET `name` = REPLACE(`name`, 'A380', 'OpenChat'),
    `updated_at` = NOW(3)
WHERE `name` LIKE 'A380%';

UPDATE `adm_miniapp_service_item`
SET `introduction` = REPLACE(`introduction`, 'A380', 'OpenChat'),
    `updated_at` = NOW(3)
WHERE `introduction` LIKE 'A380%';

UPDATE `adm_miniapp_service_item`
SET `introduction` = REPLACE(`introduction`, 'OpenChat ', ''),
    `updated_at` = NOW(3)
WHERE `name` <> 'OpenChat 服务中心'
  AND `introduction` LIKE 'OpenChat %';

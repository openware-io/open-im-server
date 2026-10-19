-- Configure the B-end operator entry exactly like the legacy A380 back office:
-- operator audience, hidden from the consumer board, but searchable and pinned.
-- The route stays /b/ so the existing SaaS B-end login and business flow are
-- unchanged; only the display identity moves to OpenChat.
UPDATE `adm_miniapp_service_item`
SET `name` = 'OpenChat 运营后台',
    `link` = '/b/',
    `introduction` = 'OpenChat 运营后台：开台、计时、结台与收款',
    `status` = 1,
    `audience` = 'operator',
    `hidden` = 1,
    `updated_at` = NOW(3)
WHERE `audience` = 'operator';

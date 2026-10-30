-- The legacy A380 service-board route is no longer served by the local Kind
-- ingress; `/a380/` intentionally falls through to saas-not-found. The current
-- C-end H5 lives at `/c/`, so relative service links must target that route.
--
-- Keep links relative so the client can resolve them against AppConfig.apiBase
-- on local Kind and production.

UPDATE `adm_miniapp_service_item`
SET `link` = CASE
        WHEN `link` = '/a380/' THEN '/c/'
        WHEN `link` LIKE '/a380/%' THEN CONCAT('/c/', SUBSTRING(`link`, 7))
        ELSE `link`
    END,
    `updated_at` = NOW(3)
WHERE `link` = '/a380/' OR `link` LIKE '/a380/%';

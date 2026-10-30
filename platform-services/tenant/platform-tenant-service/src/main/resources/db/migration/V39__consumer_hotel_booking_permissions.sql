-- C 端消费应用需要具备酒店预订的读/写权限。
-- 历史 V1 基线只给 saas-c 授权了 KTV reservation.view/create；酒店服务页会
-- 调用 /business/hotel/quotes 与 /business/hotel/bookings，缺少
-- hotel.booking.view / hotel.booking.create 时 C 端用户会被 403 拦截。
-- 迁移只追加权限并递增授权版本，使已签发的权限快照失效后重新聚合。
UPDATE `iam_consumer_application`
SET `permissions_json` = JSON_ARRAY_APPEND(
        JSON_ARRAY_APPEND(`permissions_json`, '$', 'hotel.booking.view'),
        '$', 'hotel.booking.create'),
    `authorization_version` = `authorization_version` + 1,
    `updated_at` = NOW(3)
WHERE `app_id` IN ('saas-c')
  AND JSON_CONTAINS(`permissions_json`, '"hotel.booking.view"') = 0;

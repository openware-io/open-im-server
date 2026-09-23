package io.openware.platform.admin.api.ktv;

/**
 * 服务人员目录项（KTV_BUSINESS_03_ADMIN §3）。
 * 对应 res_resource(resource_type=KTV_SERVER) + catalog item(item_type=SERVICE)。
 */
public record ServerCatalogItem(
        Long id,
        Long storeId,
        String storeName,
        String resourceCode,               // 服务人员编码，如 S01
        String name,                       // 服务人员姓名/花名
        String status,                     // ENABLED / DISABLED / MAINTENANCE
        Long catalogItemId,                // 关联目录 item id
        String billingUnit,                // HOUR / HALF_HOUR
        Integer incrementMinutes,          // 递增粒度 15/30/60
        String roundingDirection,          // CONSUMER_FAVOR / ROUND_UP / FLOOR_BLOCK
        Long pricePerIncrement,            // 每递增粒度单价（最小货币单位整数）
        Boolean participatePromotion       // 服务人员费是否参与优惠
) {}

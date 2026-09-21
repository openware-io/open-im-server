package com.gvchat.platform.admin.api.ktv;

import java.util.List;
import java.util.Map;

/**
 * 门店计价方案（KTV_BUSINESS_03_ADMIN §4，对应 KTV_BUSINESS_01_SERVICE §2）。
 * BFF 只透传/骨架返回；权威数据在 pricing 服务（后续接入）。
 */
public record PricingPlan(
        Long id,
        Long storeId,
        String storeName,
        String billingUnit,                 // HOUR / HALF_HOUR / PACKAGE，默认 HOUR
        Map<String, Long> unitPriceByRoomType, // 包厢类型 -> 每计费单位单价（最小货币单位整数），门店必填
        Long roomPricePerUnit,              // 包厢单价（最小货币单位/计费单位；元↔分换算由前端做）
        Integer incrementMinutes,           // 包厢递增粒度（分钟）
        String roundingDirection,           // 包厢舍入方向 CONSUMER_FAVOR / ROUND_UP / FLOOR_BLOCK
        Integer freeWaitMinutes,            // 免费等待分钟，默认 0
        Double overtimeRate,                // 超时费率，默认 1.0
        Integer defaultSessionMinutes,      // 标准时长（分钟），默认 120
        String roundingMode,                // 舍入规则（保留字段）
        List<PricingPackage> packages,      // 套餐，可空
        Boolean pauseEnabled,               // 暂停启用，默认 false
        String serverBillingUnit,           // 服务人员计费单位 HOUR / HALF_HOUR，默认 HOUR
        Integer serverIncrementMinutes,     // 服务人员递增粒度 15/30/60，默认 30
        String serverRoundingDirection,     // CONSUMER_FAVOR / ROUND_UP / FLOOR_BLOCK
        Long serverPricePerIncrement        // 服务人员每递增粒度单价（最小货币单位整数）
) {

    /** 固定时长固定价套餐（超出按标准单价续费）。 */
    public record PricingPackage(
            Long id,
            String name,
            Integer durationMinutes,
            Long price,                     // 一口价（最小货币单位整数）
            String status
    ) {}
}

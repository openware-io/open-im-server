package com.gvchat.platform.order.infra.config;

import com.gvchat.platform.order.domain.ktv.model.KtvBillingUnit;
import com.gvchat.platform.order.domain.ktv.model.KtvPricingPlan;
import com.gvchat.platform.order.domain.ktv.model.KtvRoundingDirection;
import com.gvchat.platform.order.domain.ktv.port.KtvPricingPlanProvider;
import com.gvchat.platform.order.infra.client.TenantPricingPlanClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 计价方案解析：**优先读租户后台「计价方案」**（tnt_pricing_plan，按门店 + 资源类型唯一），
 * 租户服务不可达或未配置方案时退回 application.yml 的 {@code ktv.pricing} 默认值。
 *
 * <p>这样「后台改价 → 开台/结台账单 → 预约页展示」是同一个价格，避免后台设置不生效。
 * 金额一律最小货币单位整数（KTV_BUSINESS_01 §5.3）。
 */
@Component
public class DefaultKtvPricingPlanProvider implements KtvPricingPlanProvider {

    private final KtvPricingProperties properties;
    private final TenantPricingPlanClient tenantPricingPlanClient;

    public DefaultKtvPricingPlanProvider(KtvPricingProperties properties,
                                         TenantPricingPlanClient tenantPricingPlanClient) {
        this.properties = properties;
        this.tenantPricingPlanClient = tenantPricingPlanClient;
    }

    @Override
    public KtvPricingPlan resolve(Long tenantId, Long storeId) {
        KtvPricingPlan fromTenantPlan = resolveFromTenantPlan(storeId);
        return fromTenantPlan != null ? fromTenantPlan : defaultPlan();
    }

    /** 当前生效的计价方案（供预约/开台等对客展示读取，与计费同源）。 */
    public KtvPricingPlan effectivePlan(Long tenantId, Long storeId) {
        return resolve(tenantId, storeId);
    }

    /**
     * 当前门店生效方案 + 指定房型的生效单价（{@code /business/ktv/pricing} 展示「实际收费单价」用）。
     * roomTypeUnitPrice/roomTypeServerUnitPrice 由调用方从资源域房型字典带入（null 表示未定价或未指定房型）。
     */
    public KtvPricingPlan effectivePlanForRoomType(Long tenantId, Long storeId, String roomTypeCode,
                                                   String roomTypeName, Long roomTypeUnitPrice,
                                                   Long roomTypeServerUnitPrice) {
        return resolve(tenantId, storeId)
                .forRoomType(roomTypeCode, roomTypeName, roomTypeUnitPrice, roomTypeServerUnitPrice);
    }

    /** 价格是否来自租户后台「计价方案」（false 表示用的是服务默认值）。 */
    public boolean usesTenantPlan(Long tenantId, Long storeId) {
        return resolveFromTenantPlan(storeId) != null;
    }

    private KtvPricingPlan resolveFromTenantPlan(Long storeId) {
        if (tenantPricingPlanClient == null) {
            return null;
        }
        var plan = tenantPricingPlanClient.findByStore(storeId).orElse(null);
        if (plan == null || plan.room() == null) {
            return null;
        }
        var room = plan.room();
        long roomUnitPrice = room.pricePerUnit() > 0 ? room.pricePerUnit() : properties.getRoomUnitPrice();
        int incrementMinutes = room.incrementMinutes() > 0 ? room.incrementMinutes() : properties.getIncrementMinutes();
        long serverPricePerHour = plan.server() != null && plan.server().pricePerUnit() > 0
                ? plan.server().pricePerUnit()
                : properties.getServerPricePerHour();
        long serverPricePerInc = serverPricePerHour * incrementMinutes / 60L;
        BigDecimal overtimeRate = room.overtimeRate() == null ? properties.getOvertimeRate() : room.overtimeRate();
        return new KtvPricingPlan(
                KtvBillingUnit.fromCode(room.billingUnit()),
                roomUnitPrice,
                room.defaultSessionMinutes() > 0 ? room.defaultSessionMinutes() : properties.getDefaultSessionMinutes(),
                properties.getFreeWaitMinutes(),
                overtimeRate,
                incrementMinutes,
                KtvRoundingDirection.fromCode(room.roundingDirection()),
                serverPricePerInc,
                // 方案按房型下发的单价（房型编码 -> 每计费单位单价）；未配置时为空映射，计费回退门店级单价。
                room.unitPriceByRoomType() == null ? Map.of() : room.unitPriceByRoomType(),
                null, null, false)
                // 新口径：包厢费基数 = 房型单价 + 服务单价（已含 1 名标准服务人员），结台按同一基数计。
                .withRoomFeeIncludesServer(true);
    }

    private KtvPricingPlan defaultPlan() {
        // 每递增粒度单价 = 小时单价 × 递增粒度 / 60（整数运算，KTV_BUSINESS_01 §5.3）。
        long serverPricePerInc = properties.getServerPricePerHour() * properties.getIncrementMinutes() / 60L;
        return new KtvPricingPlan(
                KtvBillingUnit.fromCode(properties.getBillingUnit()),
                properties.getRoomUnitPrice(),
                properties.getDefaultSessionMinutes(),
                properties.getFreeWaitMinutes(),
                properties.getOvertimeRate(),
                properties.getIncrementMinutes(),
                KtvRoundingDirection.fromCode(properties.getRoundingDirection()),
                serverPricePerInc)
                // 新口径：包厢费基数 = 房型单价 + 服务单价（已含 1 名标准服务人员）。
                .withRoomFeeIncludesServer(true);
    }
}

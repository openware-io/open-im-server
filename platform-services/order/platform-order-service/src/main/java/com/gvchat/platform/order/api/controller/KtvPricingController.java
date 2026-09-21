package com.gvchat.platform.order.api.controller;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.currency.CurrencyResolver;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.domain.ktv.model.KtvPricingPlan;
import com.gvchat.platform.order.infra.client.ResourceStateClient;
import com.gvchat.platform.order.infra.client.RoomTypeCatalogClient;
import com.gvchat.platform.order.infra.config.DefaultKtvPricingPlanProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * KTV 计价方案（只读）：约预约/开台页展示「包厢价格」用，价格与计费同源
 * （租户后台「计价方案」优先，未配置时用服务默认值）。
 *
 * <p>按房型定价：预约改为「预约房型」后预约页直接带 {@code roomTypeId}（+storeId）取该房型的合计单价；
 * 仍兼容带 {@code resourceId}（用资源的实际房型，房型单价命中则返回房型价，否则回退门店价）与
 * 直接带 {@code roomTypeCode}（只用计价方案里按房型下发的单价）。返回的
 * {@code roomUnitPrice}/{@code serverUnitPrice}/{@code combinedUnitPrice} 就是**实际生效**的单价
 * （合计 = 房型单价 + 服务单价，C 端展示口径），{@code displayText} 与计费同源。
 */
@RestController
@RequestMapping("/business/ktv/pricing")
public class KtvPricingController {

    private final DefaultKtvPricingPlanProvider pricingPlanProvider;
    private final ResourceStateClient resourceStateClient;
    private final RoomTypeCatalogClient roomTypeCatalogClient;

    @Autowired
    public KtvPricingController(DefaultKtvPricingPlanProvider pricingPlanProvider,
                                ResourceStateClient resourceStateClient,
                                RoomTypeCatalogClient roomTypeCatalogClient) {
        this.pricingPlanProvider = pricingPlanProvider;
        this.resourceStateClient = resourceStateClient;
        this.roomTypeCatalogClient = roomTypeCatalogClient;
    }

    /** 兼容既有装配（不带房型字典客户端）：按房型报价回退为门店级，行为与改造前一致。 */
    public KtvPricingController(DefaultKtvPricingPlanProvider pricingPlanProvider,
                                ResourceStateClient resourceStateClient) {
        this(pricingPlanProvider, resourceStateClient, null);
    }

    /** 兼容既有 3 参调用（不带 roomTypeId）：等价于 {@code roomTypeId=null}。 */
    public PricingView current(Long storeId, Long resourceId, String roomTypeCode) {
        return current(storeId, resourceId, roomTypeCode, null);
    }

    /**
     * 当前门店生效的计价方案（金额为最小货币单位整数）。
     *
     * @param storeId      门店；缺省用租户上下文里的门店
     * @param resourceId   包厢资源 ID；带上它才会按该包厢的实际房型取价（兼容既有调用）
     * @param roomTypeCode 房型编码；resourceId 未带或资源无房型时用它匹配计价方案的按房型单价
     * @param roomTypeId   预约房型 ID（预约页「选择包厢类型」后直接用它取价）；与 resourceId 同时传时以 resourceId 为准
     */
    @GetMapping
    public PricingView current(@RequestParam(required = false) Long storeId,
                               @RequestParam(required = false) Long resourceId,
                               @RequestParam(required = false) String roomTypeCode,
                               @RequestParam(required = false) Long roomTypeId) {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "TENANT_CONTEXT_MISSING", "缺少租户上下文");
        }
        Long effectiveStoreId = storeId != null ? storeId : context.storeId();
        ResourceStateClient.RoomSnapshot snapshot = resourceId == null || resourceStateClient == null
                ? null
                : resourceStateClient.room(resourceId).orElse(null);
        // 预约页带 roomTypeId 时不再查单个包厢：直接读房型字典（只读；读不到降级为门店级单价，不阻断页面展示）。
        RoomTypeCatalogClient.RoomTypeView catalogRoomType = snapshot != null || roomTypeId == null
                || roomTypeCatalogClient == null
                ? null
                : resolveRoomType(effectiveStoreId, roomTypeId);
        String effectiveRoomTypeCode = snapshot != null && snapshot.roomTypeCode() != null
                ? snapshot.roomTypeCode()
                : catalogRoomType != null ? catalogRoomType.code() : blankToNull(roomTypeCode);
        String effectiveRoomTypeName = snapshot != null && snapshot.roomTypeName() != null
                ? snapshot.roomTypeName()
                : catalogRoomType == null ? null : catalogRoomType.name();
        Long effectiveRoomTypeUnitPrice = snapshot != null && snapshot.roomTypeUnitPrice() != null
                ? snapshot.roomTypeUnitPrice()
                : catalogRoomType == null ? null : catalogRoomType.unitPrice();
        Long effectiveRoomTypeServerUnitPrice = snapshot != null && snapshot.roomTypeServerUnitPrice() != null
                ? snapshot.roomTypeServerUnitPrice()
                : catalogRoomType == null ? null : catalogRoomType.serverUnitPrice();
        Long effectiveRoomTypeId = snapshot != null && snapshot.roomTypeId() != null
                ? snapshot.roomTypeId()
                : catalogRoomType == null ? null : catalogRoomType.id();
        KtvPricingPlan plan = pricingPlanProvider.effectivePlanForRoomType(
                context.tenantId(), effectiveStoreId, effectiveRoomTypeCode,
                effectiveRoomTypeName, effectiveRoomTypeUnitPrice, effectiveRoomTypeServerUnitPrice);
        return new PricingView(
                plan.billingUnit().name(),
                plan.roomUnitPrice(),
                plan.incrementMinutes(),
                plan.roundingDirection().name(),
                plan.defaultSessionMinutes(),
                plan.overtimeRate(),
                plan.serverPricePerInc(),
                plan.serverUnitPrice(),
                plan.combinedUnitPrice(),
                CurrencyResolver.currentCode(),
                pricingPlanProvider.usesTenantPlan(context.tenantId(), effectiveStoreId),
                plan.unitPriceByRoomType(),
                plan.appliedRoomTypeCode(),
                plan.appliedRoomTypeName(),
                plan.roomTypePriceApplied(),
                displayText(plan),
                effectiveRoomTypeId);
    }

    /** 房型字典读路径：读不到（资源域不可达/房型已删）返回 null，由调用方降级为门店级单价。 */
    private RoomTypeCatalogClient.RoomTypeView resolveRoomType(Long storeId, Long roomTypeId) {
        if (storeId == null || roomTypeId == null) {
            return null;
        }
        try {
            return roomTypeCatalogClient.roomType(storeId, roomTypeId).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 与前端一致的展示文案，避免各端各写一套。
     * C 端「包厢价格 = 房型单价 + 服务单价」，所以首段是**合计单价**并带分项，
     * 例：{@code <币种符号>238.00/小时（房型 <币种符号>188.00 + 服务 <币种符号>50.00）· 30 分钟递增 · 标准 2.0 小时}。
     * 服务单价为 0 时不出现「+ 0.00」项，首段与改造前完全一致。
     * 「每 N 分钟 X」必须与实际计费单价同源（{@link KtvPricingPlan#combinedPricePerIncrement()}），
     * 否则页面写「30 分钟递增」而账单按整小时收，就是本次修掉的 F7 口径不一致。
     * 带房型时追加「房型 + 实际单价（或未定价回退门店价）」，页面展示与账单同源。
     */
    private static String displayText(KtvPricingPlan plan) {
        String unit = switch (plan.billingUnit()) {
            case HOUR -> "/小时";
            case HALF_HOUR -> "/半小时";
            case PACKAGE -> "/套餐";
        };
        StringBuilder text = new StringBuilder(money(plan.combinedUnitPrice())).append(unit);
        if (plan.serverUnitPrice() > 0) {
            text.append("（房型 ").append(money(plan.roomUnitPrice()))
                    .append(" + 服务 ").append(money(plan.serverUnitPrice())).append("）");
        }
        text.append(" · ").append(plan.effectiveIncrementMinutes()).append(" 分钟递增");
        if (plan.durationBillable() && plan.roomPricePerIncrement() > 0) {
            text.append(" · 每 ").append(plan.effectiveIncrementMinutes()).append(" 分钟 ")
                    .append(money(plan.combinedPricePerIncrement()));
        }
        if (plan.defaultSessionMinutes() > 0) {
            text.append(" · 标准 ").append(plan.defaultSessionMinutes() / 60.0).append(" 小时");
        }
        if (plan.appliedRoomTypeCode() != null) {
            String roomTypeName = plan.appliedRoomTypeName() == null
                    ? plan.appliedRoomTypeCode() : plan.appliedRoomTypeName();
            text.append(" · 房型「").append(roomTypeName).append("」");
            text.append(plan.roomTypePriceApplied()
                    ? "生效单价 " + money(plan.roomUnitPrice()) + unit
                    : "未定价，回退门店单价");
        }
        return text.toString();
    }

    /**
     * 最小货币单位 → 展示文案，符号取自当前上下文币种（规范 §2/§3.3）：
     * {@code CurrencyResolver.current().format(minor)} → {@code ¥188.00} / {@code $188.00}。
     *
     * <p>与响应里的 {@code currencyCode} 同源（同一 {@code CurrencyResolver}），因此服务端文案与
     * 前端渲染的符号不会再出现「同页两个符号」。
     */
    private static String money(long minor) {
        return CurrencyResolver.format(minor);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 计价方案视图（金额为最小货币单位整数）。
     *
     * @param roomUnitPrice 实际生效的包厢单价（房型价命中时即房型单价）
     * @param serverPricePerInc 实际生效的服务人员每递增粒度单价
     * @param serverUnitPrice 实际生效的服务单价（**每计费单位**，与 roomUnitPrice 同单位；仅展示/对账口径）
     * @param combinedUnitPrice 每计费单位合计单价 = roomUnitPrice + serverUnitPrice（仅展示/对账口径）
     * @param unitPriceByRoomType 计价方案按房型下发的单价（房型编码 -&gt; 每计费单位单价；未配置为空对象）
     * @param appliedRoomTypeCode 本次实际使用的房型编码（未指定房型或资源无房型时为 null）
     * @param appliedRoomTypeName 本次实际使用的房型名称
     * @param roomTypePriceApplied 是否命中房型单价（false = 该房型未定价，按门店级单价）
     * @param currencyCode 报价币种（实时预估用当前上下文币种，缺省 USD；前端据此渲染符号）
     * @param displayText 与前端一致的展示文案
     * @param appliedRoomTypeId 本次实际使用的房型 ID（预约页带 roomTypeId 报价时回显，便于前端对齐选中项；
     *                          未指定房型或房型字典读不到时为 null）
     * @param fromTenantPlan 价格是否来自租户后台「计价方案」（false = 服务默认值）
     */
    public record PricingView(String billingUnit, long roomUnitPrice, int incrementMinutes, String roundingDirection,
                              int defaultSessionMinutes, java.math.BigDecimal overtimeRate, long serverPricePerInc,
                              long serverUnitPrice, long combinedUnitPrice, String currencyCode,
                              boolean fromTenantPlan, Map<String, Long> unitPriceByRoomType,
                              String appliedRoomTypeCode, String appliedRoomTypeName, boolean roomTypePriceApplied,
                              String displayText, Long appliedRoomTypeId) {}
}

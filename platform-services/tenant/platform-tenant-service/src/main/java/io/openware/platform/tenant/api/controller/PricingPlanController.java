package io.openware.platform.tenant.api.controller;

import io.openware.platform.tenant.api.converter.PricingPlanConverter;
import io.openware.platform.tenant.api.dto.PricingPlanView;
import io.openware.platform.tenant.application.PricingPlanApplicationService;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/pricing-plans")
public class PricingPlanController {
    private final PricingPlanApplicationService pricingPlanService;

    public PricingPlanController(PricingPlanApplicationService pricingPlanService) {
        this.pricingPlanService = pricingPlanService;
    }

    /** 配置门店计价方案（KTV 包厢/服务人员，03_ADMIN §4）。 */
    @PostMapping
    public PricingPlanView create(@RequestBody CreatePricingRequest req) {
        // 上下文租户取自签名上下文，而不是请求体的 tenantId：后者是待校验项，
        // 拿它当权威来源等于跳过租户边界校验。
        return PricingPlanConverter.toView(pricingPlanService.save(
                TenantContextHolder.tenantIdOrNull(), req.tenantId(), req.storeId(), null, req.resourceType(),
                req.billingUnit(), req.incrementMinutes(), req.roundingDirection(), req.pricePerUnit(),
                req.defaultSessionMinutes(), req.overtimeRate(), null, null));
    }

    public record CreatePricingRequest(Long tenantId, Long storeId, String resourceType, String billingUnit,
                                       Integer incrementMinutes, String roundingDirection, Long pricePerUnit,
                                       Integer defaultSessionMinutes, BigDecimal overtimeRate) {}
}

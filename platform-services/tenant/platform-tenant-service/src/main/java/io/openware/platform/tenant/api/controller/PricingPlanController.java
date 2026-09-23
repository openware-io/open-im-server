package io.openware.platform.tenant.api.controller;

import io.openware.platform.tenant.infra.persistence.mapper.PricingPlanMapper;
import io.openware.platform.tenant.infra.persistence.po.PricingPlanPo;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/admin/pricing-plans")
public class PricingPlanController {
    private final PricingPlanMapper pricingPlanMapper;

    public PricingPlanController(PricingPlanMapper pricingPlanMapper) { this.pricingPlanMapper = pricingPlanMapper; }

    /** 配置门店计价方案（KTV 包厢/服务人员，03_ADMIN §4）。 */
    @PostMapping
    public PricingPlanPo create(@RequestBody CreatePricingRequest req) {
        PricingPlanPo po = new PricingPlanPo();
        po.setTenantId(req.tenantId());
        po.setStoreId(req.storeId());
        po.setResourceType(req.resourceType());
        po.setBillingUnit(req.billingUnit());
        po.setIncrementMinutes(req.incrementMinutes());
        po.setRoundingDirection(req.roundingDirection());
        po.setPricePerUnit(req.pricePerUnit());
        po.setDefaultSessionMinutes(req.defaultSessionMinutes());
        po.setOvertimeRate(req.overtimeRate());
        po.setStatus("ACTIVE");
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        pricingPlanMapper.insert(po);
        return po;
    }

    public record CreatePricingRequest(Long tenantId, Long storeId, String resourceType, String billingUnit,
                                       Integer incrementMinutes, String roundingDirection, Long pricePerUnit,
                                       Integer defaultSessionMinutes, BigDecimal overtimeRate) {}
}

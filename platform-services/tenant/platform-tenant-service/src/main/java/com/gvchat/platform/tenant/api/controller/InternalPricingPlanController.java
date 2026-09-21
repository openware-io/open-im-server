package com.gvchat.platform.tenant.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.platform.tenant.infra.persistence.mapper.PricingPlanMapper;
import com.gvchat.platform.tenant.infra.persistence.po.PricingPlanPo;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 计价方案内部端点（供 platform-admin-service BFF 调用，Gateway 不外暴露 /internal/**）。
 * 租户由 X-Tenant-Context 头经 TenantContextFilter 注入，MyBatis 租户拦截器自动附加 tenant_id。
 */
@RestController
@RequestMapping("/internal/pricing-plans")
public class InternalPricingPlanController {
    private final PricingPlanMapper pricingPlanMapper;

    public InternalPricingPlanController(PricingPlanMapper pricingPlanMapper) {
        this.pricingPlanMapper = pricingPlanMapper;
    }

    /** 门店计价方案查询（KTV_BUSINESS_03_ADMIN §4）。 */
    @GetMapping
    public List<PricingPlanPo> list(@RequestParam(required = false) Long storeId) {
        LambdaQueryWrapper<PricingPlanPo> qw = new LambdaQueryWrapper<>();
        if (storeId != null) {
            qw.eq(PricingPlanPo::getStoreId, storeId);
        }
        qw.orderByDesc(PricingPlanPo::getId);
        return pricingPlanMapper.selectList(qw);
    }

    /**
     * 计价方案保存（包厢/服务人员计费，资源类型 KTV_ROOM/KTV_SERVER）。
     * 按 (tenant, store, resourceType) **幂等 upsert**：门店计价方案唯一，重复保存只更新同一行，
     * 避免后台反复保存插出多条方案（计费取值不确定）。
     */
    @PostMapping
    public PricingPlanPo create(@RequestBody CreatePricingRequest req) {
        PricingPlanPo existing = pricingPlanMapper.selectOne(new LambdaQueryWrapper<PricingPlanPo>()
                .eq(PricingPlanPo::getTenantId, req.tenantId())
                .eq(PricingPlanPo::getStoreId, req.storeId())
                .eq(PricingPlanPo::getResourceType, req.resourceType())
                .last("LIMIT 1"));
        PricingPlanPo po = existing == null ? new PricingPlanPo() : existing;
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
        po.setUpdatedAt(LocalDateTime.now());
        if (existing == null) {
            po.setCreatedAt(LocalDateTime.now());
            pricingPlanMapper.insert(po);
        } else {
            pricingPlanMapper.updateById(po);
        }
        return po;
    }

    public record CreatePricingRequest(Long tenantId, Long storeId, String resourceType, String billingUnit,
                                       Integer incrementMinutes, String roundingDirection, Long pricePerUnit,
                                       Integer defaultSessionMinutes, BigDecimal overtimeRate) {}
}

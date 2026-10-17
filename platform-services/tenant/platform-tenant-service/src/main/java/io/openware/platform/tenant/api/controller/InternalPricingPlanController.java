package io.openware.platform.tenant.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.api.converter.PricingPlanConverter;
import io.openware.platform.tenant.domain.pricing.model.PricingPlan;
import io.openware.platform.tenant.api.dto.PricingPlanView;
import io.openware.platform.tenant.application.PricingPlanApplicationService;
import io.openware.platform.tenant.application.PricingPlanApplicationService.PricingPlanBatchCommand;
import io.openware.platform.tenant.application.PricingPlanApplicationService.PricingPlanCommand;
import io.openware.platform.tenant.application.PricingPlanApplicationService.StoreScopeValidation;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计价方案内部端点；作用域为租户默认、业态默认、门店覆盖。
 *
 * <p>作用域优先级判定在 {@code PricingPlan.scopePrecedence}、解析在 {@code PricingPlanResolver}、
 * 编排在 {@link PricingPlanApplicationService}；本类只做协议转换与上下文提取。
 */
@RestController
@RequestMapping("/internal/pricing-plans")
public class InternalPricingPlanController {
    private final PricingPlanApplicationService pricingPlanService;

    public InternalPricingPlanController(PricingPlanApplicationService pricingPlanService) {
        this.pricingPlanService = pricingPlanService;
    }

    @GetMapping
    public List<PricingPlanView> list(@RequestParam(required = false) Long storeId,
                                   @RequestParam(required = false) String businessType) {
        long tenantId = requireTenant();
        return pricingPlanService.list(tenantId, storeId, businessType).stream()
                .map(PricingPlanConverter::toView).toList();
    }

    @PostMapping
    public PricingPlanView create(@RequestBody CreatePricingRequest req) {
        return PricingPlanConverter.toView(save(requireTenant(), req));
    }

    @PostMapping("/batch")
    public BatchPricingView createBatch(@RequestBody BatchPricingRequest req) {
        long tenantId = requireTenant();
        List<PricingPlan> saved = pricingPlanService.createBatch(tenantId, toBatchCommand(req));
        return new BatchPricingView(saved.stream().map(PricingPlanConverter::toView).toList());
    }

    /** Admin/其他领域批量写入前的门店范围校验；只返回契约数据，不暴露门店 PO。 */
    @PostMapping("/store-scope/validate")
    public StoreScopeValidation validateStoreScope(@RequestBody StoreScopeRequest req) {
        long tenantId = requireTenant();
        if (req == null || req.storeIds() == null || req.storeIds().isEmpty()) {
            throw new ApiException(400, "STORE_SCOPE_INVALID", "storeIds 不能为空");
        }
        return pricingPlanService.validateStoreScope(tenantId, req.businessType(), req.storeIds());
    }

    /** 协议 DTO → 应用层命令：批量写入的校验/循环由应用服务承担。 */
    private static PricingPlanBatchCommand toBatchCommand(BatchPricingRequest req) {
        if (req == null) {
            return null;
        }
        CreatePricingRequest plan = req.plan();
        PricingPlanCommand command = plan == null ? null : new PricingPlanCommand(plan.businessType(),
                plan.resourceType(), plan.billingUnit(), plan.incrementMinutes(), plan.roundingDirection(),
                plan.pricePerUnit(), plan.defaultSessionMinutes(), plan.overtimeRate(), plan.version());
        return new PricingPlanBatchCommand(command, req.storeIds(), req.idempotencyKey());
    }

    private PricingPlan save(long tenantId,
                                                                             CreatePricingRequest req) {
        return pricingPlanService.save(tenantId, req.tenantId(), req.storeId(), req.businessType(),
                req.resourceType(), req.billingUnit(), req.incrementMinutes(), req.roundingDirection(),
                req.pricePerUnit(), req.defaultSessionMinutes(), req.overtimeRate(), req.version(),
                req.idempotencyKey());
    }

    private long requireTenant() {
        var context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        return context.tenantId();
    }

    public record CreatePricingRequest(Long tenantId, Long storeId, String businessType, String resourceType,
                                       String billingUnit, Integer incrementMinutes, String roundingDirection,
                                       Long pricePerUnit, Integer defaultSessionMinutes, BigDecimal overtimeRate,
                                       Integer version, String idempotencyKey) {}

    public record BatchPricingRequest(CreatePricingRequest plan, List<Long> storeIds, String idempotencyKey) {}
    public record BatchPricingView(List<PricingPlanView> items) {}
    public record StoreScopeRequest(String businessType, List<Long> storeIds) {}
}

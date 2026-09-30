package io.openware.platform.customer.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.customer.application.PointRuleConfigApplicationService;
import io.openware.platform.customer.infra.persistence.po.CstPointRuleConfigPo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** Customer 积分规则内部 v1 端口；Admin 只能经内部签名调用，不能跨库写配置。 */
@RestController
@RequestMapping("/internal/customer/point-rules")
public class InternalPointRuleController {
    private final PointRuleConfigApplicationService service;
    private final HttpServletRequest request;

    public InternalPointRuleController(PointRuleConfigApplicationService service, HttpServletRequest request) {
        this.service = service;
        this.request = request;
    }

    @GetMapping
    public PointRuleConfigApplicationService.PointRule get(@RequestParam(required = false) Long storeId,
                                                           @RequestParam(required = false) String businessType) {
        verify();
        TenantContext context = requireContext();
        return service.resolve(context.tenantId(), storeId, businessType);
    }

    @PutMapping
    public CstPointRuleConfigPo save(@RequestBody SaveRequest body) {
        verify();
        TenantContext context = requireContext();
        Long storeId = body.storeId();
        if (storeId != null && storeId > 0 && context.storeId() != null && !storeId.equals(context.storeId())) {
            throw new ApiException(403, "STORE_SCOPE_FORBIDDEN", "配置门店不在当前签名作用域");
        }
        return service.save(new PointRuleConfigApplicationService.SaveCommand(context.tenantId(), storeId,
                body.businessType(), body.earnRate(), body.redeemRate(), body.expiryDays(), body.redeemCapPoints(),
                body.version(), body.idempotencyKey()));
    }

    private TenantContext requireContext() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        return context;
    }

    private void verify() {
        if (!Boolean.TRUE.equals(request.getAttribute(InternalServiceAuthenticationFilter.AUTHENTICATED_ATTRIBUTE))) {
            throw new ApiException(401, "INVALID_INTERNAL_SERVICE_AUTHENTICATION", "内部服务鉴权失败");
        }
    }

    public record SaveRequest(Long storeId, String businessType, java.math.BigDecimal earnRate,
                              java.math.BigDecimal redeemRate, Integer expiryDays, Long redeemCapPoints,
                              Integer version, String idempotencyKey) {}
}

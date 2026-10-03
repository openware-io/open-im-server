package io.openware.platform.order.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.VoidRuleApplicationService;
import io.openware.platform.order.infra.persistence.po.OrdVoidRuleConfigPo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** Order 作废审批规则内部 v1 端口；规则值归 Order 域，Admin 只作 BFF 转发。 */
@RestController
@RequestMapping("/internal/order/void-rules")
public class InternalVoidRuleController {
    private final VoidRuleApplicationService service;
    private final HttpServletRequest request;

    public InternalVoidRuleController(VoidRuleApplicationService service, HttpServletRequest request) {
        this.service = service;
        this.request = request;
    }

    @GetMapping
    public VoidRuleApplicationService.RuleView get(@RequestParam(required = false) Long storeId,
                                                    @RequestParam(required = false) String businessType) {
        verify();
        TenantContext context = requireContext();
        return service.resolve(context.tenantId(), businessType, storeId);
    }

    @PutMapping
    public OrdVoidRuleConfigPo save(@RequestBody SaveRequest body) {
        verify();
        TenantContext context = requireContext();
        if (body.storeId() != null && body.storeId() > 0 && context.storeId() != null
                && !body.storeId().equals(context.storeId())) {
            throw new ApiException(403, "STORE_SCOPE_FORBIDDEN", "配置门店不在当前签名作用域");
        }
        return service.save(context.tenantId(), new VoidRuleApplicationService.SaveCommand(
                body.businessType(), body.storeId(), body.requireApproval(), body.version(), body.idempotencyKey()));
    }

    private TenantContext requireContext() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        return context;
    }

    private void verify() {
        if (!Boolean.TRUE.equals(request.getAttribute(InternalServiceAuthenticationFilter.AUTHENTICATED_ATTRIBUTE))) {
            throw new ApiException(401, "INVALID_INTERNAL_SERVICE_AUTHENTICATION", "内部服务鉴权失败");
        }
    }

    public record SaveRequest(Long storeId, String businessType, Boolean requireApproval,
                              Integer version, String idempotencyKey) { }
}

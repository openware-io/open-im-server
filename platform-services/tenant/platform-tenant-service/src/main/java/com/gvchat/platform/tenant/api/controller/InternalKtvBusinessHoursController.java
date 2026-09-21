package com.gvchat.platform.tenant.api.controller;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.tenant.application.KtvBusinessHoursApplicationService;
import com.gvchat.platform.tenant.application.KtvBusinessHoursApplicationService.BusinessHoursView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 营业时间**内部只读**端点（服务间调用，Gateway 不外暴露 /internal/**）：
 * {@code GET /internal/tenant/business-hours?storeId=} → 生效营业时间（门店行 → 租户默认行 → 缺省）。
 *
 * <p>调用方：platform-order-service 在**创建预约**时校验「到店时间必须落在营业时段内」。
 * 规则只有服务端这一处判定，客户端（C 端/B 端/后台）只用它约束选择器，不能各自实现一遍。
 */
@RestController
@RequestMapping("/internal/tenant/business-hours")
public class InternalKtvBusinessHoursController {

    private final KtvBusinessHoursApplicationService businessHoursService;

    public InternalKtvBusinessHoursController(KtvBusinessHoursApplicationService businessHoursService) {
        this.businessHoursService = businessHoursService;
    }

    @GetMapping
    public BusinessHoursView get(@RequestParam(required = false) Long storeId) {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "TENANT_CONTEXT_MISSING", "缺少租户上下文");
        }
        Long scopedStoreId = storeId != null ? storeId : context.storeId();
        return BusinessHoursView.of(scopedStoreId, businessHoursService.resolve(context.tenantId(), scopedStoreId));
    }
}

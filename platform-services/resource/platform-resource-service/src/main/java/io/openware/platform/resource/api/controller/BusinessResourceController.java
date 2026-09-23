package io.openware.platform.resource.api.controller;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.resource.application.ResourceStateApplicationService;
import io.openware.platform.resource.application.ResourceStateApplicationService.ResourceView;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * C 端/B 端业务资源只读（任选包厢、开台选包厢）：返回指定类型下资源的**运行状态**。
 * 可用性同时考虑：启用状态 + 有效占用（HELD/RESERVED/IN_USE）+ 清洁状态（清洁中不可用）。
 * 租户经 X-Tenant-Context（TenantFilterConfig）注入，MyBatis 租户拦截器自动附加 tenant_id。
 */
@RestController
@RequestMapping("/business/resources")
public class BusinessResourceController {
    private final ResourceStateApplicationService resourceStateService;

    public BusinessResourceController(ResourceStateApplicationService resourceStateService) {
        this.resourceStateService = resourceStateService;
    }

    @GetMapping
    public List<ResourceView> list(@RequestParam String resourceType) {
        return resourceStateService.listWithState(resourceType, TenantContextHolder.tenantIdOrNull());
    }

    /** 置资源清洁状态：清洁中的包厢不可开台、不可预约（结台后由门店端确认清洁完成）。 */
    @PutMapping("/{resourceId}/cleaning-status")
    public ResourceView updateCleaningStatus(@PathVariable Long resourceId, @RequestBody CleaningStatusRequest request) {
        PermissionGuard.require("resource.manage");
        if (request == null || request.cleaning() == null) {
            throw new BusinessException("CLEANING_STATUS_REQUIRED", "缺少清洁状态");
        }
        return resourceStateService.setCleaning(resourceId, request.cleaning());
    }

    public record CleaningStatusRequest(Boolean cleaning) {}
}

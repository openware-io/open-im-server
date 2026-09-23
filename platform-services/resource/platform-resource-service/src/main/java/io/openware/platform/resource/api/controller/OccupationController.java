package io.openware.platform.resource.api.controller;

import io.openware.platform.resource.application.OccupationApplicationService;
import io.openware.platform.resource.infra.persistence.po.OccupationPo;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.common.exception.ApiException;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/business/resources")
public class OccupationController {
    private final OccupationApplicationService occupationService;

    public OccupationController(OccupationApplicationService occupationService) { this.occupationService = occupationService; }

    /** 占用资源（HELD）。 */
    @PostMapping("/{resourceId}/occupations")
    public OccupationPo occupy(@PathVariable Long resourceId, @RequestBody OccupyRequest req) {
        PermissionGuard.require("resource.occupy");
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0 || context.storeId() == null) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少有效的租户/门店上下文");
        }
        if (req.startAt() == null || req.endAt() == null || !req.endAt().isAfter(req.startAt())) {
            throw new ApiException(400, "OCCUPATION_TIME_INVALID", "占用时间范围无效");
        }
        return occupationService.holdResource(
                context.tenantId(), context.storeId(), resourceId,
                req.startAt(), req.endAt(), req.sourceType(), req.sourceId(), req.holdExpiresAt());
    }

    @PostMapping("/occupations/{occupationId}/release")
    public OccupationPo release(@PathVariable Long occupationId) {
        PermissionGuard.require("resource.occupy");
        requireContext();
        return occupationService.releaseOccupation(occupationId);
    }

    @PostMapping("/occupations/{occupationId}/cancel")
    public OccupationPo cancel(@PathVariable Long occupationId) {
        PermissionGuard.require("resource.occupy");
        requireContext();
        return occupationService.cancelOccupation(occupationId);
    }

    private TenantContext requireContext() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0 || context.storeId() == null) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少有效的租户/门店上下文");
        }
        return context;
    }

    public record OccupyRequest(Long tenantId, Long storeId, LocalDateTime startAt, LocalDateTime endAt,
                                String sourceType, Long sourceId, LocalDateTime holdExpiresAt) {}
}

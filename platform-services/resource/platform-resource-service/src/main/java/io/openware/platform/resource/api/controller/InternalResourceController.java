package io.openware.platform.resource.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.resource.application.OccupationApplicationService;
import io.openware.platform.resource.application.ResourceStateApplicationService;
import io.openware.platform.resource.infra.persistence.mapper.ResourceMapper;
import io.openware.platform.resource.infra.persistence.mapper.RoomTypeMapper;
import io.openware.platform.resource.infra.persistence.po.OccupationPo;
import io.openware.platform.resource.infra.persistence.po.ResourcePo;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 资源内部端点（Gateway 不外暴露 /internal/**）：
 * - 供 platform-admin-service BFF 查询服务人员资源；
 * - 供 platform-order-service 在开台/结台/转台时占用、释放包厢，并同步清洁状态。
 * 租户由 X-Tenant-Context 头经 TenantContextFilter 注入，MyBatis 租户拦截器自动附加 tenant_id。
 */
@RestController
@RequestMapping("/internal/resources")
public class InternalResourceController {
    private final ResourceMapper resourceMapper;
    private final RoomTypeMapper roomTypeMapper;
    private final OccupationApplicationService occupationService;
    private final ResourceStateApplicationService resourceStateService;

    public InternalResourceController(ResourceMapper resourceMapper,
                                      RoomTypeMapper roomTypeMapper,
                                      OccupationApplicationService occupationService,
                                      ResourceStateApplicationService resourceStateService) {
        this.resourceMapper = resourceMapper;
        this.roomTypeMapper = roomTypeMapper;
        this.occupationService = occupationService;
        this.resourceStateService = resourceStateService;
    }

    /** 资源查询（resourceType=KTV_SERVER / KTV_ROOM）。 */
    @GetMapping
    public List<ResourcePo> list(@RequestParam(required = false) String resourceType,
                                 @RequestParam(required = false) Long storeId) {
        LambdaQueryWrapper<ResourcePo> qw = new LambdaQueryWrapper<>();
        if (resourceType != null && !resourceType.isBlank()) {
            qw.eq(ResourcePo::getResourceType, resourceType);
        }
        if (storeId != null) {
            qw.eq(ResourcePo::getStoreId, storeId);
        }
        qw.orderByDesc(ResourcePo::getId);
        return resourceMapper.selectList(qw);
    }

    /**
     * 单个资源（开台时取包厢名称/编码 + 区域 + 房型做快照，并作为「按房型定价」的取值依据）。
     * 房型字段（roomTypeCode/roomTypeName/roomTypeUnitPrice/roomTypeServerUnitPrice）为读时回填的非持久化列，
     * 让 order 域在不直连资源库的前提下拿到房型与房型单价。
     */
    @GetMapping("/{resourceId}")
    public ResourcePo get(@PathVariable Long resourceId) {
        ResourcePo resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new BusinessException("RESOURCE_NOT_FOUND", "资源不存在");
        }
        if (resource.getRoomTypeId() != null) {
            ResourceController.applyRoomType(resource, roomTypeMapper.selectById(resource.getRoomTypeId()));
        }
        return resource;
    }

    /** 资源运行状态（含清洁状态与不可用原因）。 */
    @GetMapping("/{resourceId}/state")
    public ResourceStateApplicationService.ResourceView state(@PathVariable Long resourceId) {
        return resourceStateService.view(resourceId, TenantContextHolder.tenantIdOrNull());
    }

    /** 占用资源（开台/预约占用）；时段冲突由占用服务判定并拒绝。 */
    @PostMapping("/{resourceId}/occupations")
    public OccupationPo occupy(@PathVariable Long resourceId, @RequestBody InternalOccupyRequest request) {
        TenantContext context = requireContext();
        LocalDateTime startAt = request.startAt() == null ? LocalDateTime.now() : request.startAt();
        LocalDateTime endAt = request.endAt() == null ? startAt.plusHours(24) : request.endAt();
        return occupationService.holdResource(context.tenantId(), context.storeId(), resourceId,
                startAt, endAt,
                request.sourceType() == null || request.sourceType().isBlank() ? "ORDER" : request.sourceType(),
                request.sourceId(), request.holdExpiresAt());
    }

    /** 释放占用（结台/转台）。 */
    @PostMapping("/occupations/{occupationId}/release")
    public OccupationPo release(@PathVariable Long occupationId) {
        return occupationService.releaseOccupation(occupationId);
    }

    /** 取消占用（取消开台）。 */
    @PostMapping("/occupations/{occupationId}/cancel")
    public OccupationPo cancel(@PathVariable Long occupationId) {
        return occupationService.cancelOccupation(occupationId);
    }

    /** 置清洁状态：结台后自动置 CLEANING，清洁完成置 IDLE。 */
    @PutMapping("/{resourceId}/cleaning-status")
    public ResourceStateApplicationService.ResourceView updateCleaningStatus(
            @PathVariable Long resourceId, @RequestBody CleaningStatusRequest request) {
        if (request == null || request.cleaning() == null) {
            throw new BusinessException("CLEANING_STATUS_REQUIRED", "缺少清洁状态");
        }
        return resourceStateService.setCleaning(resourceId, request.cleaning());
    }

    private TenantContext requireContext() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0 || context.storeId() == null) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少有效的租户/门店上下文");
        }
        return context;
    }

    public record InternalOccupyRequest(String sourceType, Long sourceId, LocalDateTime startAt, LocalDateTime endAt,
                                        LocalDateTime holdExpiresAt) {}
    public record CleaningStatusRequest(Boolean cleaning) {}
}

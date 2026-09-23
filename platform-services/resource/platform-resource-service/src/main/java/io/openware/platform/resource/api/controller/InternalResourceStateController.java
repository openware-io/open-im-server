package io.openware.platform.resource.api.controller;

import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.resource.application.ResourceStateApplicationService;
import io.openware.platform.resource.application.ResourceStateApplicationService.ResourceView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 资源运行态的**内部批量只读**端点（服务间调用，非客户端接口）：
 * {@code GET /internal/resource-states?resourceType=KTV_ROOM&storeId=} → 该门店该类型下
 * <b>启用中</b>资源的运行状态（available/state/unavailableReason/房型）。
 *
 * <p><b>为什么需要它</b>：预约「到店分配包厢」的候选列表必须一次拿到「门店 + 房型下的包厢 → 是否可用」，
 * 而 {@code /internal/resources} 只回 resource 表本身（没有占用/清洁运行态），
 * 逐包厢调 {@code /internal/resources/{id}/state} 又是 N+1。这里按门店一次返回，供 order 域组装候选。
 *
 * <p>单独一个 {@code /internal/resource-states} 前缀而不是挂在 {@code /internal/resources} 下，
 * 是为了避免与 {@code /internal/resources/{resourceId}} 的路径变量抢占匹配。
 */
@RestController
@RequestMapping("/internal/resource-states")
public class InternalResourceStateController {

    private final ResourceStateApplicationService resourceStateService;

    public InternalResourceStateController(ResourceStateApplicationService resourceStateService) {
        this.resourceStateService = resourceStateService;
    }

    /** 门店内指定类型资源的运行态列表（只读、按租户上下文过滤）。 */
    @GetMapping
    public List<ResourceView> list(@RequestParam String resourceType,
                                   @RequestParam(required = false) Long storeId) {
        TenantContext context = TenantContextHolder.get();
        Long tenantId = context == null ? null : context.tenantId();
        Long scopedStoreId = storeId != null ? storeId : (context == null ? null : context.storeId());
        return resourceStateService.listWithState(resourceType, tenantId, scopedStoreId);
    }
}

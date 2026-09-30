package io.openware.platform.tenant.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.application.TenantOverviewApplicationService;
import io.openware.platform.tenant.infra.persistence.po.StorePo;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant 域总部实时门店/业态只读汇总端口。 */
@RestController
@RequestMapping("/internal/tenant/overview")
public class InternalTenantOverviewController {
  private final TenantOverviewApplicationService overviewService;
  private final HttpServletRequest request;

  public InternalTenantOverviewController(TenantOverviewApplicationService overviewService,
      HttpServletRequest request) {
    this.overviewService = overviewService;
    this.request = request;
  }

  @GetMapping
  public TenantOverviewResponse overview(@RequestParam(required = false) String storeIds,
      @RequestParam(required = false) String businessType) {
    verifyInternalAuth();
    TenantContext context = TenantContextHolder.get();
    if (context == null || context.tenantId() <= 0) {
      throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
    }
    List<Long> ids = parseStoreIds(storeIds);
    List<StorePo> stores = overviewService.stores(context.tenantId(), ids, businessType);
    List<StoreOverview> result = stores.stream().map(store -> new StoreOverview(store.getId(), store.getCode(),
        store.getName(), store.getBusinessType(), store.getStatus())).toList();
    return new TenantOverviewResponse(context.tenantId(), result, Instant.now());
  }

  private void verifyInternalAuth() {
    if (!Boolean.TRUE.equals(request.getAttribute(InternalServiceAuthenticationFilter.AUTHENTICATED_ATTRIBUTE))) {
      throw new ApiException(401, "INVALID_INTERNAL_SERVICE_AUTHENTICATION", "内部服务鉴权失败");
    }
  }

  private static List<Long> parseStoreIds(String value) {
    if (value == null || value.isBlank()) return List.of();
    try {
      List<Long> ids = Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank())
          .map(Long::valueOf).toList();
      if (ids.isEmpty() || ids.stream().anyMatch(id -> id <= 0)) throw new NumberFormatException();
      return ids;
    } catch (RuntimeException e) {
      throw new ApiException(400, "STORE_FILTER_INVALID", "门店筛选非法");
    }
  }

  public record TenantOverviewResponse(long tenantId, List<StoreOverview> stores, Instant updatedAt) {}

  public record StoreOverview(long id, String code, String name, String businessType, String status) {}
}

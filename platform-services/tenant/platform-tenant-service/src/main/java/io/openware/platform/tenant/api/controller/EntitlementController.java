package io.openware.platform.tenant.api.controller;

import io.openware.platform.tenant.application.EntitlementApplicationService;
import io.openware.platform.tenant.application.EntitlementApplicationService.EntitlementView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 租户权益查询（内部与后台）。 */
@RestController
@RequestMapping
public class EntitlementController {

  private final EntitlementApplicationService service;

  public EntitlementController(EntitlementApplicationService service) {
    this.service = service;
  }

  @GetMapping("/admin/entitlements")
  public List<EntitlementView> list() {
    return service.list();
  }

  @PostMapping("/admin/platform/entitlements")
  public void grantPlatform(@RequestBody PlatformGrantRequest request) {
    service.grantPlatform(request.tenantId(), request.featureCode(), request.status(), request.source());
  }

  public record PlatformGrantRequest(Long tenantId, String featureCode, String status, String source) {
  }

  @GetMapping("/internal/iam/tenants/{tenantId}/entitlements")
  public List<EntitlementView> internalList(@PathVariable Long tenantId) {
    return service.listForTenant(tenantId);
  }
}

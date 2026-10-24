package io.openware.platform.tenant.api.controller;

import io.openware.platform.tenant.application.AppInstallationApplicationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 服务入口内部查询：identity 按 tenant 读取已安装应用。 */
@RestController
@RequestMapping("/internal/iam")
public class InternalAppInstallationController {

  private final AppInstallationApplicationService service;

  public InternalAppInstallationController(AppInstallationApplicationService service) {
    this.service = service;
  }

  @GetMapping("/tenants/{tenantId}/apps")
  public List<AppInstallationController.AppView> apps(@PathVariable Long tenantId) {
    return service.listForTenant(tenantId).stream()
        .map(AppInstallationController.AppView::of).toList();
  }
}

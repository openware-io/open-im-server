package io.openware.platform.tenant.api.controller;

import io.openware.platform.tenant.application.AppInstallationApplicationService;
import io.openware.platform.tenant.domain.app.model.AppInstallation;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 租户应用安装与内部服务入口查询。 */
@RestController
@RequestMapping("/admin/apps")
public class AppInstallationController {

  private final AppInstallationApplicationService service;

  public AppInstallationController(AppInstallationApplicationService service) {
    this.service = service;
  }

  @GetMapping
  public List<AppView> list() {
    return service.list().stream().map(AppView::of).toList();
  }

  @PostMapping
  public AppView install(@RequestBody InstallRequest request) {
    return AppView.of(service.install(request.appId(), request.clientType()));
  }

  @DeleteMapping("/{appId}")
  public void uninstall(@PathVariable String appId) {
    service.uninstall(appId);
  }

  public record InstallRequest(String appId, String clientType) {
  }

  public record AppView(Long id, Long tenantId, String appId, String clientType, String status) {
    public static AppView of(AppInstallation app) {
      return new AppView(app.id(), app.tenantId(), app.appId(), app.clientType(), app.status());
    }
  }
}

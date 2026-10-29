package io.openware.platform.tenant.api.controller;

import io.openware.platform.tenant.application.PlatformRoleBindingApplicationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 平台自定义角色生命周期入口。 */
@RestController
@RequestMapping("/admin/iam/platform/roles")
public class PlatformRoleAdminController {

  private final PlatformRoleBindingApplicationService service;

  public PlatformRoleAdminController(PlatformRoleBindingApplicationService service) {
    this.service = service;
  }

  @PostMapping("/{roleId}/disable")
  public void disable(@PathVariable Long roleId) {
    service.disableRole(roleId);
  }
}

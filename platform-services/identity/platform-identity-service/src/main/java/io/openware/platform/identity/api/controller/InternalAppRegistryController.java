package io.openware.platform.identity.api.controller;

import io.openware.platform.identity.infra.security.SaasAppRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内部应用注册表查询：tenant 安装时必须校验 appId/clientType。 */
@RestController
@RequestMapping("/internal/identity/apps")
public class InternalAppRegistryController {

  private final SaasAppRegistry registry;

  public InternalAppRegistryController(SaasAppRegistry registry) {
    this.registry = registry;
  }

  @GetMapping("/{appId}")
  public AppInfo get(@PathVariable String appId) {
    return new AppInfo(appId, registry.requireType(appId).name(), "ACTIVE");
  }

  public record AppInfo(String appId, String clientType, String status) {
  }
}

package io.openware.platform.tenant.api.controller;

import io.openware.platform.tenant.application.PlatformOperatorApplicationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 平台运营人员及其平台角色绑定只读入口。 */
@RestController
@RequestMapping("/admin/iam/platform/operators")
public class PlatformOperatorController {

  private final PlatformOperatorApplicationService service;

  public PlatformOperatorController(PlatformOperatorApplicationService service) {
    this.service = service;
  }

  @GetMapping
  public List<PlatformOperatorApplicationService.PlatformOperatorView> list() {
    return service.list();
  }
}

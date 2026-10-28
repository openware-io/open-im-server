package io.openware.platform.tenant.api.controller;

import io.openware.platform.tenant.application.PlatformRoleBindingApplicationService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 平台角色授予/撤销入口（S6）。 */
@RestController
@RequestMapping("/admin/iam/platform/role-bindings")
public class PlatformRoleBindingController {

  private final PlatformRoleBindingApplicationService service;

  public PlatformRoleBindingController(PlatformRoleBindingApplicationService service) {
    this.service = service;
  }

  @GetMapping
  public List<Long> list(@RequestParam Long accountId) {
    return service.list(accountId);
  }

  @PostMapping
  public IdResponse assign(@RequestBody AssignRequest request) {
    return new IdResponse(service.assign(request.accountId(), request.roleId()));
  }

  @DeleteMapping("/{id}")
  public void revoke(@PathVariable Long id) {
    service.revoke(id);
  }

  public record AssignRequest(Long accountId, Long roleId) {
  }

  public record IdResponse(Long id) {
  }
}

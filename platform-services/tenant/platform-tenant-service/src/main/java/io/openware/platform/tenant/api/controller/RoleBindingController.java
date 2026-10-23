package io.openware.platform.tenant.api.controller;

import io.openware.platform.tenant.application.RoleBindingApplicationService;
import io.openware.platform.tenant.domain.iam.model.RoleBinding;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 成员角色绑定 API。 */
@RestController
public class RoleBindingController {

  private final RoleBindingApplicationService service;

  public RoleBindingController(RoleBindingApplicationService service) {
    this.service = service;
  }

  @GetMapping("/admin/memberships/{membershipId}/role-bindings")
  public List<RoleBindingView> list(@PathVariable Long membershipId) {
    return service.listByMembership(membershipId).stream().map(RoleBindingView::of).toList();
  }

  @PostMapping("/admin/memberships/{membershipId}/role-bindings")
  public RoleBindingView assign(@PathVariable Long membershipId,
                                @RequestBody AssignRoleBindingRequest request) {
    return RoleBindingView.of(service.assign(membershipId, request.roleId(), request.scopeType(),
        request.organizationId(), request.storeId()));
  }

  @PostMapping("/admin/role-bindings/{id}/revoke")
  public RoleBindingView revoke(@PathVariable Long id) {
    return RoleBindingView.of(service.revoke(id));
  }

  public record AssignRoleBindingRequest(Long roleId, String scopeType,
                                         Long organizationId, Long storeId) {
  }

  public record RoleBindingView(Long id, Long membershipId, Long roleId, String scopeType,
                                Long tenantId, Long organizationId, Long storeId,
                                int authorizationVersion, String status) {
    static RoleBindingView of(RoleBinding binding) {
      return new RoleBindingView(binding.id(), binding.membershipId(), binding.roleId(),
          binding.scopeType(), binding.tenantId(), binding.organizationId(), binding.storeId(),
          binding.authorizationVersion(), binding.status());
    }
  }
}

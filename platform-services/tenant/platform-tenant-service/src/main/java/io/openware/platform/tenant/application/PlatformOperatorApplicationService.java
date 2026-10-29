package io.openware.platform.tenant.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.domain.iam.model.Role;
import io.openware.platform.tenant.domain.iam.repository.PlatformRoleBindingRepository;
import io.openware.platform.tenant.domain.iam.repository.RoleRepository;
import io.openware.platform.tenant.infrastructure.client.IdentityAccountClient;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 平台运营人员账号与平台角色绑定只读视图。 */
@Service
public class PlatformOperatorApplicationService {

  private final IdentityAccountClient accounts;
  private final RoleRepository roles;
  private final PlatformRoleBindingRepository bindings;

  public PlatformOperatorApplicationService(IdentityAccountClient accounts, RoleRepository roles,
                                            PlatformRoleBindingRepository bindings) {
    this.accounts = accounts;
    this.roles = roles;
    this.bindings = bindings;
  }

  @Transactional(readOnly = true)
  public List<PlatformOperatorView> list() {
    requirePlatformRead();
    Map<Long, Role> roleById = roles.listPlatformRoles().stream()
        .collect(Collectors.toMap(Role::id, Function.identity()));
    return accounts.listPlatformOperators().stream()
        .map(account -> new PlatformOperatorView(
            account.id(),
            account.loginIdentifier(),
            account.status(),
            bindings.findPlatformBindingsByAccount(account.id()).stream()
                .map(binding -> {
                  Role role = roleById.get(binding.roleId());
                  return new PlatformRoleView(binding.id(), binding.roleId(),
                      role == null ? null : role.code(), role == null ? null : role.name());
                })
                .toList()))
        .toList();
  }

  private static void requirePlatformRead() {
    TenantContext context = TenantContextHolder.get();
    if (context == null || !context.platformScope()) {
      throw new ApiException(403, "PLATFORM_CONTEXT_REQUIRED", "需要平台上下文");
    }
    PermissionGuard.require("platform.iam.role.view");
  }

  public record PlatformOperatorView(Long accountId, String loginIdentifier, String status,
                                     List<PlatformRoleView> roles) {
  }

  public record PlatformRoleView(Long bindingId, Long roleId, String roleCode, String roleName) {
  }
}

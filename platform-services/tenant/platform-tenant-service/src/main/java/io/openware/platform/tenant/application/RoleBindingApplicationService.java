package io.openware.platform.tenant.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.domain.iam.model.Role;
import io.openware.platform.tenant.domain.iam.model.RoleBinding;
import io.openware.platform.tenant.domain.iam.repository.OrganizationRepository;
import io.openware.platform.tenant.domain.iam.repository.RoleBindingRepository;
import io.openware.platform.tenant.domain.iam.repository.RoleRepository;
import io.openware.platform.tenant.domain.membership.Membership;
import io.openware.platform.tenant.domain.membership.MembershipRepository;
import io.openware.platform.tenant.domain.store.repository.StoreRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 成员角色绑定：只允许 B 端 STAFF 成员，角色 scope 必须与绑定 scope 一致。 */
@Service
public class RoleBindingApplicationService {

  private final RoleBindingRepository bindings;
  private final MembershipRepository memberships;
  private final RoleRepository roles;
  private final StoreRepository stores;
  private final OrganizationRepository organizations;
  private final PermissionSnapshotProvider snapshots;

  public RoleBindingApplicationService(RoleBindingRepository bindings,
                                       MembershipRepository memberships,
                                       RoleRepository roles,
                                       StoreRepository stores,
                                       OrganizationRepository organizations,
                                       PermissionSnapshotProvider snapshots) {
    this.bindings = bindings;
    this.memberships = memberships;
    this.roles = roles;
    this.stores = stores;
    this.organizations = organizations;
    this.snapshots = snapshots;
  }

  @Transactional
  public RoleBinding assign(Long membershipId, Long roleId, String scopeType,
                            Long organizationId, Long storeId) {
    TenantContext context = requireTenant();
    Membership membership = memberships.findById(membershipId)
        .orElseThrow(() -> new ApiException(404, "MEMBERSHIP_NOT_FOUND", "成员身份不存在"));
    if (!membership.tenantId().equals(context.tenantId())) {
      throw new ApiException(404, "MEMBERSHIP_NOT_FOUND", "成员身份不存在");
    }
    if (!Membership.TYPE_STAFF.equals(membership.membershipType())) {
      throw new ApiException(400, "STAFF_MEMBERSHIP_REQUIRED", "只有员工成员身份可以绑定后台角色");
    }
    if (!membership.active()) {
      throw new ApiException(400, "MEMBERSHIP_INACTIVE", "成员身份不可用");
    }
    Role role = roles.findById(roleId)
        .orElseThrow(() -> new ApiException(404, "ROLE_NOT_FOUND", "角色不存在"));
    if (!role.active()) {
      throw new ApiException(400, "ROLE_INACTIVE", "角色不可用");
    }
    if (role.tenantId() != null && !role.tenantId().equals(context.tenantId())) {
      throw new ApiException(400, "ROLE_TENANT_MISMATCH", "角色不属于当前租户");
    }
    if (!scopeType.equals(role.scopeLevel())) {
      throw new ApiException(400, "ROLE_SCOPE_MISMATCH", "角色作用域与绑定作用域不一致");
    }
    if (RoleBinding.SCOPE_STORE.equals(scopeType) && (storeId == null || storeId <= 0)) {
      throw new ApiException(400, "STORE_SCOPE_REQUIRED", "门店角色必须指定门店");
    }
    if (RoleBinding.SCOPE_ORGANIZATION.equals(scopeType)
        && (organizationId == null || organizationId <= 0)) {
      throw new ApiException(400, "ORGANIZATION_SCOPE_REQUIRED", "组织角色必须指定组织");
    }
    if (organizationId != null && organizationId > 0
        && !organizations.findTenantId(organizationId).filter(id -> id == context.tenantId()).isPresent()) {
      throw new ApiException(400, "ORGANIZATION_TENANT_MISMATCH", "组织不属于当前租户");
    }
    if (storeId != null && storeId > 0
        && !stores.findOwnerTenantId(storeId).filter(id -> id == context.tenantId()).isPresent()) {
      throw new ApiException(400, "STORE_TENANT_MISMATCH", "门店不属于当前租户");
    }
    if (bindings.findActive(membershipId, roleId, scopeType, context.tenantId(), organizationId, storeId)
        .isPresent()) {
      throw new ApiException(409, "ROLE_BINDING_EXISTS", "该角色绑定已存在");
    }
    RoleBinding saved = bindings.save(RoleBinding.create(membershipId, roleId, scopeType,
        context.tenantId(), organizationId, storeId));
    memberships.save(membership.bumpAuthorizationVersion());
    snapshots.evict(membership.accountId());
    return saved;
  }

  @Transactional
  public RoleBinding assignByAccount(Long accountId, Long tenantId, Long organizationId, Long storeId,
                                     Long roleId, String scopeType) {
    TenantContext context = requireTenant();
    Long resolvedTenant = tenantId == null ? context.tenantId() : tenantId;
    if (!resolvedTenant.equals(context.tenantId())) {
      throw new ApiException(403, "TENANT_SCOPE_DENIED", "不能操作其他租户的成员");
    }
    Membership membership = memberships
        .findByAccountAndTenantAndType(accountId, resolvedTenant, Membership.TYPE_STAFF)
        .orElseThrow(() -> new ApiException(404, "STAFF_MEMBERSHIP_NOT_FOUND", "员工成员身份不存在"));
    return assign(membership.id(), roleId, scopeType, organizationId, storeId);
  }

  public List<RoleBinding> listByAccount(Long accountId) {
    TenantContext context = TenantContextHolder.get();
    return memberships.findAllByAccountId(accountId).stream()
        .filter(membership -> context == null || context.platformScope()
            || membership.tenantId().equals(context.tenantId()))
        .flatMap(membership -> bindings.findByMembership(membership.id()).stream())
        .toList();
  }

  public List<Long> listIdsByAccount(Long accountId) {
    return listByAccount(accountId).stream().map(RoleBinding::id).toList();
  }

  @Transactional
  public void revokeByAccountAndRole(Long accountId, Long roleId) {
    TenantContext context = requireTenant();
    RoleBinding target = listByAccount(accountId).stream()
        .filter(RoleBinding::active)
        .filter(binding -> binding.roleId().equals(roleId))
        .filter(binding -> binding.tenantId().equals(context.tenantId()))
        .findFirst()
        .orElseThrow(() -> new ApiException(404, "ROLE_BINDING_NOT_FOUND", "成员角色绑定不存在"));
    bindings.save(target.revoke());
    touchMembership(target);
  }

  @Transactional
  public RoleBinding updateScopeByAccountAndRole(Long accountId, Long roleId, Long organizationId, Long storeId,
                                          String scopeType) {
    TenantContext context = requireTenant();
    RoleBinding target = listByAccount(accountId).stream()
        .filter(RoleBinding::active)
        .filter(binding -> binding.roleId().equals(roleId))
        .filter(binding -> binding.tenantId().equals(context.tenantId()))
        .findFirst()
        .orElseThrow(() -> new ApiException(404, "ROLE_BINDING_NOT_FOUND", "成员角色绑定不存在"));
    RoleBinding saved = bindings.save(target.changeScope(scopeType, organizationId, storeId));
    touchMembership(target);
    return saved;
  }

  public Long accountIdForBinding(Long bindingId) {
    RoleBinding binding = bindings.findById(bindingId)
        .orElseThrow(() -> new ApiException(404, "ROLE_BINDING_NOT_FOUND", "角色绑定不存在"));
    return memberships.findById(binding.membershipId())
        .orElseThrow(() -> new ApiException(404, "MEMBERSHIP_NOT_FOUND", "成员身份不存在"))
        .accountId();
  }

  public List<RoleBinding> listByMembership(Long membershipId) {
    TenantContext context = requireTenant();
    Membership membership = memberships.findById(membershipId)
        .orElseThrow(() -> new ApiException(404, "MEMBERSHIP_NOT_FOUND", "成员身份不存在"));
    if (!membership.tenantId().equals(context.tenantId())) {
      throw new ApiException(404, "MEMBERSHIP_NOT_FOUND", "成员身份不存在");
    }
    return bindings.findByMembership(membershipId);
  }

  @Transactional
  public RoleBinding revoke(Long bindingId) {
    TenantContext context = requireTenant();
    RoleBinding binding = bindings.findById(bindingId)
        .orElseThrow(() -> new ApiException(404, "ROLE_BINDING_NOT_FOUND", "角色绑定不存在"));
    if (!binding.tenantId().equals(context.tenantId())) {
      throw new ApiException(404, "ROLE_BINDING_NOT_FOUND", "角色绑定不存在");
    }
    RoleBinding saved = bindings.save(binding.revoke());
    touchMembership(binding);
    return saved;
  }

  private void touchMembership(RoleBinding binding) {
    Membership membership = memberships.findById(binding.membershipId())
        .orElseThrow(() -> new ApiException(404, "MEMBERSHIP_NOT_FOUND", "成员身份不存在"));
    memberships.save(membership.bumpAuthorizationVersion());
    snapshots.evict(membership.accountId());
  }

  private TenantContext requireTenant() {
    TenantContext context = TenantContextHolder.get();
    if (context == null || context.platformScope()) {
      throw new ApiException(401, "SAAS_TENANT_CONTEXT_REQUIRED", "缺少租户上下文");
    }
    return context;
  }
}

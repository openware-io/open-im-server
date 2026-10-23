package io.openware.platform.tenant.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.domain.iam.model.BindingDescriptor;
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
  private final AuditClient audit;

  public RoleBindingApplicationService(RoleBindingRepository bindings,
                                       MembershipRepository memberships,
                                       RoleRepository roles,
                                       StoreRepository stores,
                                       OrganizationRepository organizations,
                                       PermissionSnapshotProvider snapshots,
                                       AuditClient audit) {
    this.bindings = bindings;
    this.memberships = memberships;
    this.roles = roles;
    this.stores = stores;
    this.organizations = organizations;
    this.snapshots = snapshots;
    this.audit = audit;
  }

  @Transactional
  public RoleBinding assign(Long membershipId, Long roleId, String scopeType,
                            Long organizationId, Long storeId) {
    if ("PLATFORM".equals(scopeType)) {
      throw new ApiException(400, "PLATFORM_BINDING_OUT_OF_SCOPE",
          "平台角色授予不属于 S2，请走 S3/S4 平台运营入口");
    }
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
        && !organizations.findTenantId(organizationId).filter(id -> id.equals(context.tenantId())).isPresent()) {
      throw new ApiException(400, "ORGANIZATION_TENANT_MISMATCH", "组织不属于当前租户");
    }
    if (storeId != null && storeId > 0
        && !stores.findOwnerTenantId(storeId).filter(id -> id.equals(context.tenantId())).isPresent()) {
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
    audit.recordAsync(AuditClient.AuditRecord.of(context.tenantId(), null, "iam.role_binding.assign",
        "role_binding", String.valueOf(saved.id()), null, "role-binding:" + saved.id(),
        "{\"membershipId\":" + membershipId + ",\"roleId\":" + roleId + "}"));
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
    if (context == null) {
      throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少上下文");
    }
    return memberships.findAllByAccountId(accountId).stream()
        .filter(membership -> context.platformScope()
            || membership.tenantId().equals(context.tenantId()))
        .flatMap(membership -> bindings.findByMembership(membership.id()).stream())
        .toList();
  }

  public List<Long> listIdsByAccount(Long accountId) {
    TenantContext context = TenantContextHolder.get();
    if (context == null) {
      throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少上下文");
    }
    java.util.ArrayList<Long> ids = new java.util.ArrayList<>(
        listByAccount(accountId).stream().map(RoleBinding::id).toList());
    if (context.platformScope()) {
      ids.addAll(bindings.findPlatformIdsByAccount(accountId));
    }
    return ids;
  }

  @Transactional
  public void revokeByAccountAndRole(Long accountId, Long roleId) {
    TenantContext context = requireTenant();
    java.util.List<RoleBinding> targets = listByAccount(accountId).stream()
        .filter(RoleBinding::active)
        .filter(binding -> binding.roleId().equals(roleId))
        .filter(binding -> binding.tenantId().equals(context.tenantId()))
        .toList();
    if (targets.isEmpty()) {
      throw new ApiException(404, "ROLE_BINDING_NOT_FOUND", "成员角色绑定不存在");
    }
    for (RoleBinding target : targets) {
      bindings.save(target.revoke());
      touchMembership(target);
    }
    audit.recordAsync(AuditClient.AuditRecord.of(context.tenantId(), null, "iam.role_binding.revoke",
        "role_binding", String.valueOf(roleId), null, "role-binding-revoke-role:" + roleId,
        "{\"accountId\":" + accountId + ",\"count\":" + targets.size() + "}"));
  }
  @Transactional
  public RoleBinding updateScopeByAccountAndRole(Long accountId, Long roleId, Long organizationId, Long storeId,
                                          String scopeType) {
    if (scopeType == null || scopeType.isBlank()) {
      throw new ApiException(400, "ROLE_SCOPE_REQUIRED", "作用域不能为空");
    }
    if ("PLATFORM".equals(scopeType)) {
      throw new ApiException(400, "PLATFORM_BINDING_OUT_OF_SCOPE",
          "平台角色授予不属于 S2，请走 S3/S4 平台运营入口");
    }
    TenantContext context = requireTenant();
    Role role = roles.findById(roleId)
        .orElseThrow(() -> new ApiException(404, "ROLE_NOT_FOUND", "角色不存在"));
    if (!role.active() || !scopeType.equals(role.scopeLevel())) {
      throw new ApiException(400, "ROLE_SCOPE_MISMATCH", "角色作用域与绑定作用域不一致");
    }
    if (organizationId != null && organizationId > 0
        && !organizations.findTenantId(organizationId).filter(id -> id.equals(context.tenantId())).isPresent()) {
      throw new ApiException(400, "ORGANIZATION_TENANT_MISMATCH", "组织不属于当前租户");
    }
    if (storeId != null && storeId > 0
        && !stores.findOwnerTenantId(storeId).filter(id -> id.equals(context.tenantId())).isPresent()) {
      throw new ApiException(400, "STORE_TENANT_MISMATCH", "门店不属于当前租户");
    }
    java.util.List<RoleBinding> targets = listByAccount(accountId).stream()
        .filter(RoleBinding::active)
        .filter(binding -> binding.roleId().equals(roleId))
        .filter(binding -> binding.tenantId().equals(context.tenantId()))
        .toList();
    if (targets.isEmpty()) {
      throw new ApiException(404, "ROLE_BINDING_NOT_FOUND", "成员角色绑定不存在");
    }
    RoleBinding saved = null;
    for (RoleBinding target : targets) {
      saved = bindings.save(target.changeScope(scopeType, organizationId, storeId));
      touchMembership(target);
      audit.recordAsync(AuditClient.AuditRecord.of(context.tenantId(), null, "iam.role_binding.scope",
          "role_binding", String.valueOf(saved.id()), null, "role-binding-scope:" + saved.id(),
          "{\"scopeType\":\"" + scopeType + "\"}"));
    }
    return saved;
  }

  public Long accountIdForBinding(Long bindingId) {
    return bindings.findAccountIdByAnyId(bindingId)
        .orElseThrow(() -> new ApiException(404, "ROLE_BINDING_NOT_FOUND", "角色绑定不存在"));
  }

  @Transactional
  public void revokeAny(Long bindingId) {
    TenantContext context = TenantContextHolder.get();
    if (context == null) {
      throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少上下文");
    }
    BindingDescriptor descriptor = bindings.findDescriptor(bindingId)
        .orElseThrow(() -> new ApiException(404, "ROLE_BINDING_NOT_FOUND", "角色绑定不存在"));
    boolean platformBinding = "ACCOUNT".equals(descriptor.subjectType())
        || "PLATFORM".equals(descriptor.scopeType());
    if (platformBinding) {
      if (!context.platformScope()) {
        throw new ApiException(404, "ROLE_BINDING_NOT_FOUND", "角色绑定不存在");
      }
    } else if (!context.platformScope() && !descriptor.tenantId().equals(context.tenantId())) {
      throw new ApiException(404, "ROLE_BINDING_NOT_FOUND", "角色绑定不存在");
    }
    Long accountId = bindings.findAccountIdByAnyId(bindingId)
        .orElseThrow(() -> new ApiException(404, "ROLE_BINDING_NOT_FOUND", "角色绑定不存在"));
    if (!bindings.revokeAnyById(bindingId)) {
      throw new ApiException(404, "ROLE_BINDING_NOT_FOUND", "角色绑定不存在");
    }
    if (!platformBinding) {
      membershipBump(descriptor.subjectId());
    }
    snapshots.evict(accountId);
    audit.recordAsync(AuditClient.AuditRecord.of(context.tenantId(), null, "iam.role_binding.revoke_any",
        "role_binding", String.valueOf(bindingId), null, "role-binding-revoke-any:" + bindingId,
        "{\"accountId\":" + accountId + "}"));
  }

  private void membershipBump(Long membershipId) {
    Membership membership = memberships.findById(membershipId)
        .orElseThrow(() -> new ApiException(404, "MEMBERSHIP_NOT_FOUND", "成员身份不存在"));
    memberships.save(membership.bumpAuthorizationVersion());
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
    audit.recordAsync(AuditClient.AuditRecord.of(context.tenantId(), null, "iam.role_binding.revoke",
        "role_binding", String.valueOf(bindingId), null, "role-binding-revoke:" + bindingId,
        "{\"membershipId\":" + binding.membershipId() + "}"));
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

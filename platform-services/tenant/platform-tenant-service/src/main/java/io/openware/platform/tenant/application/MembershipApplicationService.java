package io.openware.platform.tenant.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.domain.iam.repository.RoleBindingRepository;
import io.openware.platform.tenant.domain.membership.Membership;
import io.openware.platform.tenant.domain.membership.MembershipRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 租户成员应用服务：创建、查询、状态变更；授权主体为 membership。 */
@Service
public class MembershipApplicationService {

  private final MembershipRepository repository;
  private final RoleBindingRepository bindings;
  private final PermissionSnapshotProvider snapshots;
  private final AuditClient audit;

  public MembershipApplicationService(MembershipRepository repository,
                                       RoleBindingRepository bindings,
                                       PermissionSnapshotProvider snapshots,
                                       AuditClient audit) {
    this.repository = repository;
    this.bindings = bindings;
    this.snapshots = snapshots;
    this.audit = audit;
  }

  @Transactional
  public Membership create(Long accountId, Long organizationId, String type, String memberNo) {
    TenantContext context = requireTenant();
    return createForTenant(context.tenantId(), accountId, organizationId, type, memberNo);
  }

  @Transactional
  public Membership createForTenant(Long tenantId, Long accountId, Long organizationId,
                                    String type, String memberNo) {
    repository.findByAccountAndTenantAndType(accountId, tenantId, type).ifPresent(existing -> {
      throw new ApiException(409, "MEMBERSHIP_EXISTS", "该账号在此租户已存在同类型成员身份");
    });
    Membership created = repository.save(
        Membership.create(accountId, tenantId, organizationId, type, memberNo));
    snapshots.evict(accountId);
    audit.recordAsync(AuditClient.AuditRecord.of(tenantId, null, "iam.membership.create", "membership",
        String.valueOf(created.id()), created.memberNo(), "membership:" + created.id(),
        "{\"accountId\":" + accountId + ",\"type\":\"" + type + "\"}"));
    return created;
  }

  /** C 端首次应用授权：幂等创建 CUSTOMER membership。 */
  @Transactional
  public Membership ensureConsumer(Long tenantId, Long accountId) {
    return repository.findByAccountAndTenantAndType(accountId, tenantId, Membership.TYPE_CUSTOMER)
        .orElseGet(() -> createForTenant(tenantId, accountId, 0L, Membership.TYPE_CUSTOMER,
            "C" + accountId));
  }

  public Membership get(Long id) {
    return repository.findById(id)
        .orElseThrow(() -> new ApiException(404, "MEMBERSHIP_NOT_FOUND", "成员身份不存在"));
  }

  public List<Membership> list(String type, String status) {
    TenantContext context = requireTenant();
    return repository.findByTenant(context.tenantId(), type, status);
  }

  public List<Membership> listByAccount(Long accountId) {
    return repository.findAllByAccountId(accountId);
  }

  @Transactional
  public Membership changeStatus(Long id, String status) {
    Membership current = get(id);
    TenantContext context = requireTenant();
    if (!current.tenantId().equals(context.tenantId())) {
      throw new ApiException(404, "MEMBERSHIP_NOT_FOUND", "成员身份不存在");
    }
    if (!Membership.STATUS_ACTIVE.equals(status)) {
      bindings.findByMembership(id).stream()
          .filter(binding -> binding.active())
          .forEach(binding -> bindings.save(binding.revoke()));
    }
    Membership saved = repository.save(current.changeStatus(status));
    snapshots.evict(current.accountId());
    audit.recordAsync(AuditClient.AuditRecord.of(current.tenantId(), null, "iam.membership.status",
        "membership", String.valueOf(id), current.memberNo(), "membership-status:" + id + ":" + status,
        "{\"accountId\":" + current.accountId() + ",\"status\":\"" + status + "\"}"));
    return saved;
  }

  private TenantContext requireTenant() {
    TenantContext context = TenantContextHolder.get();
    if (context == null || context.platformScope()) {
      throw new ApiException(401, "SAAS_TENANT_CONTEXT_REQUIRED", "缺少租户上下文");
    }
    return context;
  }
}

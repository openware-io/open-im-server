package io.openware.platform.tenant.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.domain.iam.model.Role;
import io.openware.platform.tenant.domain.iam.repository.RoleRepository;
import io.openware.platform.tenant.infra.persistence.mapper.RoleBindingMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 平台 ACCOUNT 角色授予/撤销（S6）。 */
@Service
public class PlatformRoleBindingApplicationService {

  private static final String PERMISSION = "platform.iam.role.manage";

  private final RoleBindingMapper mapper;
  private final RoleRepository roles;
  private final AuditClient audit;

  public PlatformRoleBindingApplicationService(RoleBindingMapper mapper, RoleRepository roles,
                                               AuditClient audit) {
    this.mapper = mapper;
    this.roles = roles;
    this.audit = audit;
  }

  @Transactional
  public Long assign(Long accountId, Long roleId) {
    TenantContext context = requirePlatform();
    if (context.permissions() == null || !context.permissions().contains(PERMISSION)) {
      throw new ApiException(403, "PERMISSION_DENIED", "缺少平台角色授予权限");
    }
    Role role = roles.findById(roleId)
        .orElseThrow(() -> new ApiException(404, "ROLE_NOT_FOUND", "角色不存在"));
    if (!role.active() || !Role.SCOPE_PLATFORM.equals(role.scopeLevel())) {
      throw new ApiException(400, "PLATFORM_ROLE_REQUIRED", "只能授予平台级角色");
    }
    Long existing = mapper.selectPlatformId(accountId, roleId);
    if (existing != null) {
      return existing;
    }
    mapper.insertPlatform(accountId, roleId);
    Long id = mapper.selectPlatformId(accountId, roleId);
    audit.recordAsync(AuditClient.AuditRecord.of(0L, context.accountId(),
        "iam.platform_role.assign", "iam_role_binding", String.valueOf(id), null,
        "platform-role:" + accountId + ":" + roleId,
        "{\"accountId\":" + accountId + ",\"roleId\":" + roleId + "}"));
    return id;
  }

  @Transactional
  public void revoke(Long bindingId) {
    TenantContext context = requirePlatform();
    if (context.permissions() == null || !context.permissions().contains(PERMISSION)) {
      throw new ApiException(403, "PERMISSION_DENIED", "缺少平台角色授予权限");
    }
    if (mapper.revokePlatform(bindingId) == 0) {
      throw new ApiException(404, "ROLE_BINDING_NOT_FOUND", "平台角色绑定不存在");
    }
    audit.recordAsync(AuditClient.AuditRecord.of(0L, context.accountId(),
        "iam.platform_role.revoke", "iam_role_binding", String.valueOf(bindingId), null,
        "platform-role-revoke:" + bindingId, "{}"));
  }

  public List<Long> list(Long accountId) {
    requirePlatform();
    return mapper.selectPlatformIdsByAccount(accountId);
  }

  private static TenantContext requirePlatform() {
    TenantContext context = TenantContextHolder.get();
    if (context == null || !context.platformScope()) {
      throw new ApiException(403, "PLATFORM_CONTEXT_REQUIRED", "需要平台上下文");
    }
    return context;
  }
}

package io.openware.platform.tenant.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.infra.persistence.mapper.EntitlementMapper;
import java.util.List;
import org.springframework.stereotype.Service;

/** 租户权益：商业开通，不等同于角色权限。 */
@Service
public class EntitlementApplicationService {

  private final EntitlementMapper mapper;
  private final AuditClient audit;

  public EntitlementApplicationService(EntitlementMapper mapper, AuditClient audit) {
    this.mapper = mapper;
    this.audit = audit;
  }

  public List<EntitlementView> list() {
    TenantContext context = TenantContextHolder.get();
    if (context == null || context.tenantId() <= 0) {
      throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
    }
    return listForTenant(context.tenantId());
  }

  public List<EntitlementView> listForTenant(Long tenantId) {
    if (tenantId == null || tenantId <= 0) {
      throw new ApiException(400, "TENANT_REQUIRED", "tenantId 不能为空");
    }
    return mapper.selectActive(tenantId).stream()
        .map(row -> new EntitlementView(row.getBusinessType(), row.getStatus(), row.getSource()))
        .toList();
  }

  public void grant(String featureCode, String status) {
    TenantContext context = TenantContextHolder.get();
    if (context == null || context.tenantId() <= 0) {
      throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
    }
    PermissionGuard.require("tenant.business.manage");
    if (featureCode == null || featureCode.isBlank()) {
      throw new ApiException(400, "FEATURE_REQUIRED", "featureCode 不能为空");
    }
    String normalizedStatus = status == null || status.isBlank() ? "ACTIVE" : status.toUpperCase();
    if (!List.of("ACTIVE", "SUSPENDED", "CLOSED").contains(normalizedStatus)) {
      throw new ApiException(400, "ENTITLEMENT_STATUS_INVALID", "权益状态非法");
    }
    mapper.upsert(context.tenantId(), featureCode.trim().toUpperCase(), normalizedStatus);
    audit.recordAsync(AuditClient.AuditRecord.of(context.tenantId(), context.accountId(),
        "iam.entitlement.grant", "business_entitlement", featureCode, null,
        "entitlement:" + context.tenantId() + ":" + featureCode,
        "{\"featureCode\":\"" + featureCode + "\",\"status\":\"" + normalizedStatus + "\"}"));
  }

  public record EntitlementView(String featureCode, String status, String source) {
  }
}

package io.openware.platform.tenant.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.domain.entitlement.repository.EntitlementRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** 租户权益：商业开通，不等同于角色权限。 */
@Service
public class EntitlementApplicationService {

  private final EntitlementRepository repository;
  private final AuditClient audit;

  public EntitlementApplicationService(EntitlementRepository repository, AuditClient audit) {
    this.repository = repository;
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
    return repository.findActive(tenantId).stream()
        .map(row -> new EntitlementView(row.featureCode(), row.status(), row.source()))
        .toList();
  }

  public void grant(String featureCode, String status) {
    throw new ApiException(403, "ENTITLEMENT_PLATFORM_ONLY", "商业权益只能由平台开通");
  }

  public void grantPlatform(Long tenantId, String featureCode, String status, String source) {
    TenantContext context = TenantContextHolder.get();
    if (context == null || !context.platformScope()) {
      throw new ApiException(403, "PLATFORM_CONTEXT_REQUIRED", "需要平台上下文");
    }
    if (context.permissions() == null
        || !context.permissions().contains("platform.tenant.entitlement.manage")) {
      throw new ApiException(403, "PERMISSION_DENIED", "缺少平台权益开通权限");
    }
    if (tenantId == null || tenantId <= 0) {
      throw new ApiException(400, "TENANT_REQUIRED", "tenantId 不能为空");
    }
    if (featureCode == null || featureCode.isBlank()) {
      throw new ApiException(400, "FEATURE_REQUIRED", "featureCode 不能为空");
    }
    String normalizedStatus = status == null || status.isBlank() ? "ACTIVE" : status.toUpperCase();
    if (!List.of("ACTIVE", "SUSPENDED", "CLOSED").contains(normalizedStatus)) {
      throw new ApiException(400, "ENTITLEMENT_STATUS_INVALID", "权益状态非法");
    }
    String normalizedSource = source == null || source.isBlank() ? "MANUAL" : source.toUpperCase();
    if (!List.of("MANUAL", "SUBSCRIPTION").contains(normalizedSource)) {
      throw new ApiException(400, "ENTITLEMENT_SOURCE_INVALID", "权益来源非法");
    }
    repository.upsert(tenantId, featureCode.trim().toUpperCase(), normalizedStatus, normalizedSource);
    audit.recordAsync(AuditClient.AuditRecord.of(tenantId, context.accountId(),
        "iam.entitlement.grant", "business_entitlement", featureCode, null,
        "entitlement:" + tenantId + ":" + featureCode,
        "{\"featureCode\":\"" + featureCode + "\",\"status\":\"" + normalizedStatus
            + "\",\"source\":\"" + normalizedSource + "\"}"));
  }

  public record EntitlementView(String featureCode, String status, String source) {
  }
}

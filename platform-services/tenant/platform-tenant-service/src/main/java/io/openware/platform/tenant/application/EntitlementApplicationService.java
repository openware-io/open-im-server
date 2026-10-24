package io.openware.platform.tenant.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.infra.persistence.mapper.EntitlementMapper;
import java.util.List;
import org.springframework.stereotype.Service;

/** 租户权益：商业开通，不等同于角色权限。 */
@Service
public class EntitlementApplicationService {

  private final EntitlementMapper mapper;

  public EntitlementApplicationService(EntitlementMapper mapper) {
    this.mapper = mapper;
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

  public record EntitlementView(String featureCode, String status, String source) {
  }
}

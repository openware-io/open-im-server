package io.openware.platform.tenant.application;

import io.openware.common.exception.ApiException;
import io.openware.platform.tenant.domain.store.model.Store;
import io.openware.platform.tenant.domain.store.repository.StoreRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Tenant 域总部门店总览用例；查询仅访问 Tenant 域门店事实。 */
@Service
public class TenantOverviewApplicationService {
  private final StoreRepository storeRepository;

  public TenantOverviewApplicationService(StoreRepository storeRepository) {
    this.storeRepository = storeRepository;
  }

  public List<Store> stores(long tenantId, List<Long> storeIds, String businessType) {
    String normalized = null;
    if (businessType != null && !businessType.isBlank()) {
      normalized = businessType.trim().toUpperCase(Locale.ROOT);
      // 业态取值的权威来源是字典表：字典里没有（或已停用）即拒绝，而不是静默返回空列表。
      if (storeRepository.findActiveBusinessTypeCode(normalized) == null) {
        throw new ApiException(400, "BUSINESS_TYPE_INVALID", "业态筛选无效");
      }
    }
    return storeRepository.listByTenant(tenantId, storeIds, normalized);
  }
}

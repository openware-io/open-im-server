package io.openware.platform.tenant.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.openware.platform.tenant.infra.persistence.mapper.StoreMapper;
import io.openware.platform.tenant.infra.persistence.po.StorePo;
import java.util.List;
import io.openware.common.exception.ApiException;
import org.springframework.stereotype.Service;

/** Tenant 域总部门店总览用例；查询仅访问 Tenant 域门店事实。 */
@Service
public class TenantOverviewApplicationService {
  private final StoreMapper storeMapper;

  public TenantOverviewApplicationService(StoreMapper storeMapper) {
    this.storeMapper = storeMapper;
  }

  public List<StorePo> stores(long tenantId, List<Long> storeIds, String businessType) {
    QueryWrapper<StorePo> query = new QueryWrapper<>();
    query.eq("tenant_id", tenantId);
    if (storeIds != null && !storeIds.isEmpty()) query.in("id", storeIds);
    if (businessType != null && !businessType.isBlank()) {
      String normalized = businessType.trim().toUpperCase(java.util.Locale.ROOT);
      if (storeMapper.selectActiveBusinessType(normalized) == null) {
        throw new ApiException(400, "BUSINESS_TYPE_INVALID", "业态筛选无效");
      }
      query.eq("business_type", normalized);
    }
    query.orderByAsc("id");
    return storeMapper.selectList(query);
  }
}

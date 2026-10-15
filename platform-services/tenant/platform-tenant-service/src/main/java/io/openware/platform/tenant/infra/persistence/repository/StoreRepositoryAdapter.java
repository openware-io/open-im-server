package io.openware.platform.tenant.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.tenant.domain.store.model.Store;
import io.openware.platform.tenant.domain.store.repository.StoreRepository;
import io.openware.platform.tenant.infra.persistence.mapper.StoreMapper;
import io.openware.platform.tenant.infra.persistence.po.StorePo;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link StoreRepository} 的持久化适配器。
 *
 * <p>业态字典的查询走 mapper 的专用语句（字典表 {@code tnt_business_type}），在映射层原样透出，
 * 不把"字典表存在"这件事扩散到应用层。
 */
@Repository
public class StoreRepositoryAdapter implements StoreRepository {

  private final StoreMapper mapper;

  public StoreRepositoryAdapter(StoreMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<Store> listByTenant(Long tenantId, List<Long> storeIds, String businessType) {
    return mapper.selectList(Wrappers.<StorePo>lambdaQuery()
            .eq(StorePo::getTenantId, tenantId)
            .in(storeIds != null && !storeIds.isEmpty(), StorePo::getId, storeIds)
            .eq(businessType != null && !businessType.isBlank(), StorePo::getBusinessType, businessType)
            .orderByAsc(StorePo::getId))
        .stream().map(this::toDomain).toList();
  }

  @Override
  public Optional<Store> findById(Long storeId) {
    return Optional.ofNullable(mapper.selectById(storeId)).map(this::toDomain);
  }

  @Override
  public String findActiveBusinessTypeCode(String businessType) {
    return mapper.selectActiveBusinessType(businessType);
  }

  private Store toDomain(StorePo po) {
    return new Store(po.getId(), po.getTenantId(), po.getOrganizationId(), po.getCode(), po.getName(),
        po.getBusinessType(), po.getCountryCode(), po.getRegionCode(), po.getTimezone(),
        po.getDefaultCurrency(), po.getLocale(), po.getTaxProfileId(), po.getBusinessDayCutoff(),
        po.getStatus());
  }
}

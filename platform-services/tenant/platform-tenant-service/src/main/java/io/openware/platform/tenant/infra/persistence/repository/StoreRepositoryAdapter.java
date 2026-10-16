package io.openware.platform.tenant.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.tenant.domain.store.model.Store;
import io.openware.platform.tenant.domain.store.repository.StoreRepository;
import io.openware.platform.tenant.infra.persistence.mapper.StoreMapper;
import io.openware.platform.tenant.infra.persistence.po.StorePo;
import java.time.LocalDateTime;
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
  public List<Store> listByTenant(Long tenantId, List<Long> storeIds, String businessType, String status) {
    return mapper.selectList(Wrappers.<StorePo>lambdaQuery()
            .eq(StorePo::getTenantId, tenantId)
            .in(storeIds != null && !storeIds.isEmpty(), StorePo::getId, storeIds)
            .eq(businessType != null && !businessType.isBlank(), StorePo::getBusinessType, businessType)
            .eq(status != null && !status.isBlank(), StorePo::getStatus, status)
            .orderByAsc(StorePo::getId))
        .stream().map(this::toDomain).toList();
  }

  @Override
  public Optional<Store> findById(Long storeId) {
    return Optional.ofNullable(mapper.selectById(storeId)).map(this::toDomain);
  }

  @Override
  public Optional<Long> findOwnerTenantId(Long storeId) {
    return Optional.ofNullable(mapper.selectTenantIdById(storeId));
  }

  @Override
  public int changeDefaultCurrency(Long tenantId, String targetCurrencyCode) {
    // 条件 UPDATE：已在目标币种的门店不参与，受影响行数即"实际改了几家"。
    // 等价于 SQL: UPDATE tnt_store SET default_currency = ?, updated_at = ?
    //            WHERE tenant_id = ? AND default_currency <> ?
    return mapper.update(null, Wrappers.<StorePo>lambdaUpdate()
        .eq(StorePo::getTenantId, tenantId)
        .ne(StorePo::getDefaultCurrency, targetCurrencyCode)
        .set(StorePo::getDefaultCurrency, targetCurrencyCode)
        .set(StorePo::getUpdatedAt, LocalDateTime.now()));
  }

  @Override
  public boolean updateSchedule(Long storeId, String timezone, String businessDayCutoff,
                                 String businessType, Long operatorId) {
    return mapper.update(null, Wrappers.<StorePo>lambdaUpdate()
        .eq(StorePo::getId, storeId)
        .set(timezone != null, StorePo::getTimezone, timezone)
        .set(businessDayCutoff != null, StorePo::getBusinessDayCutoff, businessDayCutoff)
        .set(businessType != null, StorePo::getBusinessType, businessType)
        .set(StorePo::getUpdatedBy, operatorId)
        .set(StorePo::getUpdatedAt, LocalDateTime.now())) > 0;
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

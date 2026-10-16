package io.openware.platform.tenant.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.tenant.domain.configuration.model.TenantConfig;
import io.openware.platform.tenant.domain.configuration.repository.TenantConfigRepository;
import io.openware.platform.tenant.infra.persistence.mapper.TenantConfigMapper;
import io.openware.platform.tenant.infra.persistence.po.TenantConfigPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link TenantConfigRepository} 的持久化适配器。
 *
 * <p>审计字段由本层填充（{@code created_at}/{@code updated_at} 为 NOT NULL 无默认值）。
 */
@Repository
public class TenantConfigRepositoryAdapter implements TenantConfigRepository {

  private final TenantConfigMapper mapper;

  public TenantConfigRepositoryAdapter(TenantConfigMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<TenantConfig> findByScope(Long tenantId, Long storeId, String configKey) {
    return mapper.selectList(Wrappers.<TenantConfigPo>lambdaQuery()
            .eq(TenantConfigPo::getTenantId, tenantId)
            .eq(TenantConfigPo::getStoreId, storeId)
            .eq(TenantConfigPo::getConfigKey, configKey))
        .stream().findFirst().map(TenantConfigRepositoryAdapter::toDomain);
  }

  @Override
  public List<TenantConfig> listByScope(Long tenantId, Long storeId) {
    return mapper.selectList(Wrappers.<TenantConfigPo>lambdaQuery()
            .eq(TenantConfigPo::getTenantId, tenantId)
            .eq(TenantConfigPo::getStoreId, storeId))
        .stream().map(TenantConfigRepositoryAdapter::toDomain).toList();
  }

  @Override
  public TenantConfig upsert(TenantConfig draft) {
    LocalDateTime now = LocalDateTime.now();
    Optional<TenantConfig> existing = findByScope(draft.tenantId(), draft.storeId(), draft.configKey());
    if (existing.isPresent()) {
      TenantConfig current = existing.get();
      // 只改值：作用域列（租户/门店/业态/键）是这条记录的身份，改它们等于把配置搬到别的作用域。
      mapper.update(null, Wrappers.<TenantConfigPo>lambdaUpdate()
          .eq(TenantConfigPo::getId, current.id())
          .set(TenantConfigPo::getConfigValue, draft.configValue())
          .set(TenantConfigPo::getUpdatedAt, now));
      return new TenantConfig(current.id(), current.tenantId(), current.storeId(), current.businessType(),
          current.configKey(), draft.configValue(), current.status(), current.version());
    }
    TenantConfigPo po = new TenantConfigPo();
    po.setTenantId(draft.tenantId());
    po.setStoreId(draft.storeId());
    po.setBusinessType(draft.businessType());
    po.setConfigKey(draft.configKey());
    po.setConfigValue(draft.configValue());
    po.setStatus(draft.status());
    po.setVersion(0);
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    mapper.insert(po);
    return toDomain(po);
  }

  private static TenantConfig toDomain(TenantConfigPo po) {
    return new TenantConfig(po.getId(), po.getTenantId(), po.getStoreId(), po.getBusinessType(),
        po.getConfigKey(), po.getConfigValue(), po.getStatus(), po.getVersion());
  }
}

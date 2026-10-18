package io.openware.platform.tenant.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.tenant.domain.configuration.model.TenantConfig;
import io.openware.platform.tenant.domain.configuration.model.TenantConfigValue;
import io.openware.platform.tenant.domain.configuration.repository.TenantConfigRepository;
import io.openware.platform.tenant.infra.persistence.mapper.InternalTenantConfigMapper;
import io.openware.platform.tenant.infra.persistence.mapper.TenantConfigMapper;
import io.openware.platform.tenant.infra.persistence.po.TenantConfigPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link TenantConfigRepository} 的持久化适配器。
 *
 * <p>三层读取走 {@link InternalTenantConfigMapper} 的专用语句：它们已带
 * {@code @InterceptorIgnore(tenantLine = "true")}，正是"按显式入参定位、不受上下文租户影响"的语义。
 * 用 lambda Wrapper 重写会绕开这个注解——在有租户上下文时被静默过滤成 0 行，配置会静默回退到上一层级。
 *
 * <p>写路径与作用域查询走 {@link TenantConfigMapper}（受租户约束的常规读写）。
 * 审计字段由本层填充（{@code created_at}/{@code updated_at} 为 NOT NULL 无默认值）。
 */
@Repository
public class TenantConfigRepositoryAdapter implements TenantConfigRepository {

  private final TenantConfigMapper mapper;
  private final InternalTenantConfigMapper reader;

  public TenantConfigRepositoryAdapter(TenantConfigMapper mapper, InternalTenantConfigMapper reader) {
    this.mapper = mapper;
    this.reader = reader;
  }

  @Override
  public Optional<TenantConfig> findByScope(Long tenantId, Long storeId, String businessType, String configKey) {
    return mapper.selectList(Wrappers.<TenantConfigPo>lambdaQuery()
            .eq(TenantConfigPo::getTenantId, tenantId)
            .eq(TenantConfigPo::getStoreId, storeId)
            // 业态参与定位：空串代表"不限业态"，与 DDL 的 NOT NULL DEFAULT '' 一致。
            // 漏掉这一列会让不同业态的同名键互相覆盖。
            .eq(TenantConfigPo::getBusinessType, businessType == null ? "" : businessType)
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
    Optional<TenantConfig> existing =
        findByScope(draft.tenantId(), draft.storeId(), draft.businessType(), draft.configKey());
    if (existing.isPresent()) {
      TenantConfig current = existing.get();
      int expectedVersion = current.version() == null ? 0 : current.version();
      // 只改值：作用域列（租户/门店/业态/键）是这条记录的身份，改它们等于把配置搬到别的作用域。
      // 版本参与 CAS 并自增：并发改同一配置时，后写者必须失败而不是静默覆盖前者。
      int updated = mapper.update(null, Wrappers.<TenantConfigPo>lambdaUpdate()
          .eq(TenantConfigPo::getId, current.id())
          .eq(TenantConfigPo::getVersion, expectedVersion)
          .set(TenantConfigPo::getConfigValue, draft.configValue())
          .set(TenantConfigPo::getUpdatedAt, now)
          .setSql("version = version + 1"));
      if (updated == 0) {
        throw new IllegalStateException("TENANT_CONFIG_VERSION_CONFLICT");
      }
      return new TenantConfig(current.id(), current.tenantId(), current.storeId(), current.businessType(),
          current.configKey(), draft.configValue(), current.status(), expectedVersion + 1);
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

  @Override
  public Optional<TenantConfigValue> findStoreValue(Long tenantId, Long storeId, String businessType,
                                                    String configKey) {
    // 门店级：指定业态时按业态取，否则取不限业态的那条（空串）。
    String raw = businessType == null
        ? reader.selectStoreConfigValue(tenantId, storeId, configKey)
        : reader.selectStoreConfigValueByBusinessType(tenantId, storeId, businessType, configKey);
    return value(raw, TenantConfigValue.Scope.STORE);
  }

  @Override
  public Optional<TenantConfigValue> findBusinessValue(Long tenantId, String businessType, String configKey) {
    return value(reader.selectBusinessConfigValue(tenantId, businessType, configKey),
        TenantConfigValue.Scope.BUSINESS);
  }

  @Override
  public Optional<TenantConfigValue> findTenantValue(Long tenantId, String configKey) {
    return value(reader.selectTenantConfigValue(tenantId, configKey), TenantConfigValue.Scope.TENANT);
  }

  /** 空值与纯空白视为缺行：配置值缺失应由调用方回退到下一层级，而不是当成一个可用的空字符串。 */
  private static Optional<TenantConfigValue> value(String raw, TenantConfigValue.Scope scope) {
    return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(new TenantConfigValue(raw, scope));
  }

  private static TenantConfig toDomain(TenantConfigPo po) {
    return new TenantConfig(po.getId(), po.getTenantId(), po.getStoreId(), po.getBusinessType(),
        po.getConfigKey(), po.getConfigValue(), po.getStatus(), po.getVersion());
  }
}

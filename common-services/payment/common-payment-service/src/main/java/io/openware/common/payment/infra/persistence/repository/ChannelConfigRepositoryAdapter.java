package io.openware.common.payment.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.common.payment.domain.channel.model.ChannelConfig;
import io.openware.common.payment.domain.channel.repository.AuditSnapshot;
import io.openware.common.payment.domain.channel.repository.ChannelConfigRepository;
import io.openware.common.payment.domain.channel.repository.Persisted;
import io.openware.common.payment.infra.persistence.mapper.ChannelConfigMapper;
import io.openware.common.payment.infra.persistence.po.ChannelConfigPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link ChannelConfigRepository} 的持久化适配器。
 *
 * <p>三处值得留意的实现选择：
 * <ul>
 *   <li>租户级用 {@code IS NULL} 而不是等值：{@code = NULL} 永不成立；</li>
 *   <li>写入按作用域查行时**不过滤 status**：停用行要能被复用，否则同作用域会插入第二行并撞唯一键；</li>
 *   <li>更新是单条 CAS（{@code version = version + 1}），不是"读出来加一再写回"。</li>
 * </ul>
 */
@Repository
public class ChannelConfigRepositoryAdapter implements ChannelConfigRepository {

  private final ChannelConfigMapper mapper;

  public ChannelConfigRepositoryAdapter(ChannelConfigMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<Persisted<ChannelConfig>> listActiveByTenant(Long tenantId) {
    return mapper.selectList(Wrappers.<ChannelConfigPo>lambdaQuery()
            .eq(ChannelConfigPo::getTenantId, tenantId)
            .eq(ChannelConfigPo::getStatus, ChannelConfig.STATUS_ACTIVE))
        .stream().map(this::toPersisted).toList();
  }

  @Override
  public Optional<Persisted<ChannelConfig>> findByScope(Long tenantId, Long storeId, String businessType,
                                                        String channel) {
    return mapper.selectList(Wrappers.<ChannelConfigPo>lambdaQuery()
            .eq(ChannelConfigPo::getTenantId, tenantId)
            .eq(ChannelConfigPo::getChannel, channel)
            .eq(ChannelConfigPo::getBusinessType, businessType == null ? ChannelConfig.ANY_BUSINESS_TYPE
                : businessType)
            .isNull(storeId == null, ChannelConfigPo::getStoreId)
            .eq(storeId != null, ChannelConfigPo::getStoreId, storeId))
        .stream().findFirst().map(this::toPersisted);
  }

  @Override
  public Optional<Persisted<ChannelConfig>> findByIdempotencyKey(Long tenantId, String channel,
                                                                String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return Optional.empty();
    }
    return mapper.selectList(Wrappers.<ChannelConfigPo>lambdaQuery()
            .eq(ChannelConfigPo::getTenantId, tenantId)
            .eq(ChannelConfigPo::getChannel, channel)
            .eq(ChannelConfigPo::getIdempotencyKey, idempotencyKey))
        .stream().findFirst().map(this::toPersisted);
  }

  @Override
  public Persisted<ChannelConfig> insert(ChannelConfig config, LocalDateTime occurredAt) {
    ChannelConfigPo po = toPo(config);
    po.setVersion(0);
    po.setStatus(ChannelConfig.STATUS_ACTIVE);
    po.setCreatedAt(occurredAt);
    po.setUpdatedAt(occurredAt);
    mapper.insert(po);
    return toPersisted(po);
  }

  @Override
  public Optional<Persisted<ChannelConfig>> updateWithVersion(ChannelConfig config, int expectedVersion,
                                                              LocalDateTime occurredAt) {
    // CAS：判定与写入在同一条语句里，由数据库保证原子性；版本自增也写在 SQL 里，
    // 避免用读出来的值加一而覆盖并发写入期间已经涨上去的版本号。
    int updated = mapper.update(null, Wrappers.<ChannelConfigPo>lambdaUpdate()
        .eq(ChannelConfigPo::getId, config.id())
        .eq(ChannelConfigPo::getVersion, expectedVersion)
        .set(ChannelConfigPo::getEnabled, Boolean.TRUE.equals(config.enabled()) ? 1 : 0)
        .set(ChannelConfigPo::getMerchantId, config.merchantId())
        .set(ChannelConfigPo::getIdempotencyKey, config.idempotencyKey())
        .set(ChannelConfigPo::getUpdatedAt, occurredAt)
        .setSql("version = version + 1"));
    return updated > 0 ? Optional.ofNullable(mapper.selectById(config.id())).map(this::toPersisted)
        : Optional.empty();
  }

  @Override
  public boolean existsEnabled(Long tenantId, Long storeId, String channel) {
    return mapper.selectCount(Wrappers.<ChannelConfigPo>lambdaQuery()
        .eq(ChannelConfigPo::getTenantId, tenantId)
        .eq(ChannelConfigPo::getChannel, channel)
        .eq(ChannelConfigPo::getEnabled, 1)
        .eq(ChannelConfigPo::getStatus, ChannelConfig.STATUS_ACTIVE)
        .and(w -> w.isNull(ChannelConfigPo::getStoreId)
            .or().eq(storeId != null, ChannelConfigPo::getStoreId, storeId))) > 0;
  }

  private static ChannelConfigPo toPo(ChannelConfig config) {
    ChannelConfigPo po = new ChannelConfigPo();
    po.setId(config.id());
    po.setTenantId(config.tenantId());
    po.setStoreId(config.storeId());
    po.setBusinessType(config.businessType());
    po.setChannel(config.channel());
    po.setEnabled(Boolean.TRUE.equals(config.enabled()) ? 1 : 0);
    po.setMerchantId(config.merchantId());
    po.setStatus(config.status());
    po.setVersion(config.version());
    po.setIdempotencyKey(config.idempotencyKey());
    return po;
  }

  private static ChannelConfig toDomain(ChannelConfigPo po) {
    return new ChannelConfig(po.getId(), po.getTenantId(), po.getStoreId(), po.getBusinessType(),
        po.getChannel(), po.getEnabled() != null && po.getEnabled() == 1, po.getMerchantId(),
        po.getStatus(), po.getVersion(), po.getIdempotencyKey());
  }

  private Persisted<ChannelConfig> toPersisted(ChannelConfigPo po) {
    return new Persisted<>(toDomain(po), new AuditSnapshot(po.getCreatedAt(), po.getUpdatedAt()));
  }
}

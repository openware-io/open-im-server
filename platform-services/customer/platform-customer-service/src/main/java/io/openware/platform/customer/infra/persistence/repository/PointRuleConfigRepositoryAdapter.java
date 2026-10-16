package io.openware.platform.customer.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.customer.domain.point.model.PointRuleConfig;
import io.openware.platform.customer.domain.point.repository.PointRuleConfigRepository;
import io.openware.platform.customer.infra.persistence.mapper.PointRuleConfigMapper;
import io.openware.platform.customer.infra.persistence.po.CstPointRuleConfigPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link PointRuleConfigRepository} 的持久化适配器。
 *
 * <p>三处选择：租户级用 {@code IS NULL}-安全的等值匹配（本表 store_id 是 0 而非 NULL）；
 * 作用域查询**不过滤 status**（停用行要能被复用）；更新是单条 CAS，版本自增写在 SQL 里。
 */
@Repository
public class PointRuleConfigRepositoryAdapter implements PointRuleConfigRepository {

  private final PointRuleConfigMapper mapper;

  public PointRuleConfigRepositoryAdapter(PointRuleConfigMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<PointRuleConfig> listActiveByTenant(Long tenantId) {
    return mapper.selectList(Wrappers.<CstPointRuleConfigPo>lambdaQuery()
            .eq(CstPointRuleConfigPo::getTenantId, tenantId)
            .eq(CstPointRuleConfigPo::getStatus, PointRuleConfig.STATUS_ACTIVE))
        .stream().map(PointRuleConfigRepositoryAdapter::toDomain).toList();
  }

  @Override
  public Optional<PointRuleConfig> findByScope(Long tenantId, String businessType, Long storeId) {
    return mapper.selectList(Wrappers.<CstPointRuleConfigPo>lambdaQuery()
            .eq(CstPointRuleConfigPo::getTenantId, tenantId)
            // 业态参与定位：空串代表"不限业态"，与 DDL 的 NOT NULL DEFAULT '' 一致。
            .eq(CstPointRuleConfigPo::getBusinessType,
                businessType == null ? PointRuleConfig.ANY_BUSINESS_TYPE : businessType)
            .eq(CstPointRuleConfigPo::getStoreId,
                storeId == null ? PointRuleConfig.TENANT_LEVEL_STORE_ID : storeId))
        .stream().findFirst().map(PointRuleConfigRepositoryAdapter::toDomain);
  }

  @Override
  public Optional<PointRuleConfig> findByIdempotencyKey(Long tenantId, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return Optional.empty();
    }
    return mapper.selectList(Wrappers.<CstPointRuleConfigPo>lambdaQuery()
            .eq(CstPointRuleConfigPo::getTenantId, tenantId)
            .eq(CstPointRuleConfigPo::getIdempotencyKey, idempotencyKey))
        .stream().findFirst().map(PointRuleConfigRepositoryAdapter::toDomain);
  }

  @Override
  public PointRuleConfig insert(PointRuleConfig config) {
    LocalDateTime now = LocalDateTime.now();
    CstPointRuleConfigPo po = toPo(config);
    po.setVersion(0);
    po.setStatus(PointRuleConfig.STATUS_ACTIVE);
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    mapper.insert(po);
    return toDomain(po);
  }

  @Override
  public boolean updateWithVersion(PointRuleConfig config, int expectedVersion) {
    // CAS：判定与写入在同一条语句里。版本自增写在 SQL 里，避免用读出来的值加一而覆盖并发写入期间的版本号。
    return mapper.update(null, Wrappers.<CstPointRuleConfigPo>lambdaUpdate()
        .eq(CstPointRuleConfigPo::getId, config.id())
        .eq(CstPointRuleConfigPo::getVersion, expectedVersion)
        .set(CstPointRuleConfigPo::getEarnRate, config.earnRate())
        .set(CstPointRuleConfigPo::getRedeemRate, config.redeemRate())
        .set(CstPointRuleConfigPo::getExpiryDays, config.expiryDays())
        .set(CstPointRuleConfigPo::getRedeemCapPoints, config.redeemCapPoints())
        .set(CstPointRuleConfigPo::getIdempotencyKey, config.idempotencyKey())
        .set(CstPointRuleConfigPo::getUpdatedAt, LocalDateTime.now())
        .setSql("version = version + 1")) > 0;
  }

  private static CstPointRuleConfigPo toPo(PointRuleConfig config) {
    CstPointRuleConfigPo po = new CstPointRuleConfigPo();
    po.setId(config.id());
    po.setTenantId(config.tenantId());
    po.setBusinessType(config.businessType());
    po.setStoreId(config.storeId());
    po.setEarnRate(config.earnRate());
    po.setRedeemRate(config.redeemRate());
    po.setExpiryDays(config.expiryDays());
    po.setRedeemCapPoints(config.redeemCapPoints());
    po.setVersion(config.version());
    po.setIdempotencyKey(config.idempotencyKey());
    po.setStatus(config.status());
    return po;
  }

  private static PointRuleConfig toDomain(CstPointRuleConfigPo po) {
    return new PointRuleConfig(po.getId(), po.getTenantId(), po.getBusinessType(), po.getStoreId(),
        po.getEarnRate(), po.getRedeemRate(), po.getExpiryDays(), po.getRedeemCapPoints(),
        po.getVersion(), po.getIdempotencyKey(), po.getStatus());
  }
}

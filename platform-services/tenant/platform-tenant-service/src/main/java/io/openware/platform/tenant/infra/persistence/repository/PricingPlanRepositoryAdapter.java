package io.openware.platform.tenant.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.tenant.domain.pricing.model.PricingPlan;
import io.openware.platform.tenant.domain.pricing.repository.PricingPlanRepository;
import io.openware.platform.tenant.infra.persistence.mapper.PricingPlanMapper;
import io.openware.platform.tenant.infra.persistence.po.PricingPlanPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link PricingPlanRepository} 的持久化适配器。
 *
 * <p><b>乐观锁在这里落地为 CAS</b>：{@code updateWithVersion} 用单条带 {@code version = ?} 条件的 UPDATE
 * 完成，受影响 0 行即冲突。见端口说明中"为什么不能先读后判再写"。
 *
 * <p>审计字段由本层填充（{@code created_at}/{@code updated_at} 为 NOT NULL 无默认值）。
 */
@Repository
public class PricingPlanRepositoryAdapter implements PricingPlanRepository {

  private final PricingPlanMapper mapper;

  public PricingPlanRepositoryAdapter(PricingPlanMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<PricingPlan> findByScope(Long tenantId, Long storeId, String businessType, String resourceType) {
    return mapper.selectList(Wrappers.<PricingPlanPo>lambdaQuery()
            .eq(PricingPlanPo::getTenantId, tenantId)
            .eq(PricingPlanPo::getStoreId, storeId)
            .eq(PricingPlanPo::getBusinessType, businessType)
            .eq(PricingPlanPo::getResourceType, resourceType))
        .stream().findFirst().map(PricingPlanRepositoryAdapter::toDomain);
  }

  @Override
  public Optional<PricingPlan> findByIdempotencyKey(Long tenantId, String idempotencyKey, String resourceType) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return Optional.empty();
    }
    return mapper.selectList(Wrappers.<PricingPlanPo>lambdaQuery()
            .eq(PricingPlanPo::getTenantId, tenantId)
            .eq(PricingPlanPo::getIdempotencyKey, idempotencyKey)
            .eq(PricingPlanPo::getResourceType, resourceType))
        .stream().findFirst().map(PricingPlanRepositoryAdapter::toDomain);
  }

  @Override
  public List<PricingPlan> listActiveByTenant(Long tenantId, String businessType) {
    return mapper.selectList(Wrappers.<PricingPlanPo>lambdaQuery()
            .eq(PricingPlanPo::getTenantId, tenantId)
            .eq(PricingPlanPo::getStatus, PricingPlan.STATUS_ACTIVE)
            .eq(businessType != null && !businessType.isBlank(), PricingPlanPo::getBusinessType, businessType)
            .orderByDesc(PricingPlanPo::getId))
        .stream().map(PricingPlanRepositoryAdapter::toDomain).toList();
  }

  @Override
  public List<PricingPlan> listActiveByTenant(Long tenantId) {
    return listActiveByTenant(tenantId, null);
  }

  @Override
  public PricingPlan insert(PricingPlan plan) {
    LocalDateTime now = LocalDateTime.now();
    PricingPlanPo po = toPo(plan);
    po.setVersion(0);
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    mapper.insert(po);
    return toDomain(po);
  }

  @Override
  public boolean updateWithVersion(PricingPlan plan, int expectedVersion) {
    // CAS：判定与写入在同一条语句里，由数据库保证原子性。
    // 版本自增写在 SQL 里（version + 1）而不是用读出来的值加一，避免覆盖并发写入期间已经涨上去的版本号。
    int affected = mapper.update(null, Wrappers.<PricingPlanPo>lambdaUpdate()
        .eq(PricingPlanPo::getId, plan.id())
        .eq(PricingPlanPo::getVersion, expectedVersion)
        .set(PricingPlanPo::getBillingUnit, plan.billingUnit())
        .set(PricingPlanPo::getIncrementMinutes, plan.incrementMinutes())
        .set(PricingPlanPo::getRoundingDirection, plan.roundingDirection())
        .set(PricingPlanPo::getPricePerUnit, plan.pricePerUnit())
        .set(PricingPlanPo::getDefaultSessionMinutes, plan.defaultSessionMinutes())
        .set(PricingPlanPo::getOvertimeRate, plan.overtimeRate())
        .set(PricingPlanPo::getStatus, plan.status())
        .set(PricingPlanPo::getIdempotencyKey, plan.idempotencyKey())
        .set(PricingPlanPo::getUpdatedAt, LocalDateTime.now())
        .setSql("version = version + 1"));
    return affected > 0;
  }

  private static PricingPlanPo toPo(PricingPlan plan) {
    PricingPlanPo po = new PricingPlanPo();
    po.setId(plan.id());
    po.setTenantId(plan.tenantId());
    po.setStoreId(plan.storeId());
    po.setBusinessType(plan.businessType());
    po.setResourceType(plan.resourceType());
    po.setBillingUnit(plan.billingUnit());
    po.setIncrementMinutes(plan.incrementMinutes());
    po.setRoundingDirection(plan.roundingDirection());
    po.setPricePerUnit(plan.pricePerUnit());
    po.setDefaultSessionMinutes(plan.defaultSessionMinutes());
    po.setOvertimeRate(plan.overtimeRate());
    po.setStatus(plan.status());
    po.setVersion(plan.version());
    po.setIdempotencyKey(plan.idempotencyKey());
    return po;
  }

  private static PricingPlan toDomain(PricingPlanPo po) {
    return new PricingPlan(po.getId(), po.getTenantId(), po.getStoreId(), po.getBusinessType(),
        po.getResourceType(), po.getBillingUnit(), po.getIncrementMinutes(), po.getRoundingDirection(),
        po.getPricePerUnit(), po.getDefaultSessionMinutes(), po.getOvertimeRate(), po.getStatus(),
        po.getVersion(), po.getIdempotencyKey(), po.getCreatedAt(), po.getUpdatedAt());
  }
}

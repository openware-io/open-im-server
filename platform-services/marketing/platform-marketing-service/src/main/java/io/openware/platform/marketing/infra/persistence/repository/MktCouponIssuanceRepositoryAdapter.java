package io.openware.platform.marketing.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.marketing.domain.coupon.model.MktCouponIssuance;
import io.openware.platform.marketing.domain.coupon.repository.MktCouponIssuanceRepository;
import io.openware.platform.marketing.infra.persistence.mapper.CouponIssuanceMapper;
import io.openware.platform.marketing.infra.persistence.po.MktCouponIssuancePo;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link MktCouponIssuanceRepository} 的持久化适配器。
 *
 * <p>{@link #markRedeemed} 是单条条件更新：WHERE 同时限定标识、{@code status = ISSUED} 与版本，
 * 因此"状态已变"与"版本冲突"都会让受影响行数为 0，本次调用即判定为输；这是防重复核销的关键，
 * 不得改写为"先读后判再写"。
 *
 * <p>审计字段由本层填充（created_at/updated_at 为 NOT NULL 无默认值）。
 */
@Repository
public class MktCouponIssuanceRepositoryAdapter implements MktCouponIssuanceRepository {

  private final CouponIssuanceMapper mapper;

  public MktCouponIssuanceRepositoryAdapter(CouponIssuanceMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<MktCouponIssuance> findUnique(Long couponId, Long customerId) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<MktCouponIssuancePo>lambdaQuery()
        .eq(MktCouponIssuancePo::getCouponId, couponId)
        .eq(MktCouponIssuancePo::getCustomerId, customerId)
        .last("LIMIT 1"))).map(this::toDomain);
  }

  @Override
  public Optional<MktCouponIssuance> findByIdempotencyKey(String idempotencyKey) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<MktCouponIssuancePo>lambdaQuery()
        .eq(MktCouponIssuancePo::getIdempotencyKey, idempotencyKey)
        .last("LIMIT 1"))).map(this::toDomain);
  }

  @Override
  public MktCouponIssuance save(MktCouponIssuance issuance) {
    LocalDateTime now = LocalDateTime.now();
    MktCouponIssuancePo po = new MktCouponIssuancePo();
    po.setId(issuance.id());
    po.setTenantId(issuance.tenantId());
    po.setCouponId(issuance.couponId());
    po.setCustomerId(issuance.customerId());
    po.setIssuedAt(issuance.issuedAt());
    po.setUsedAt(issuance.usedAt());
    po.setOrderId(issuance.redemptionOrderId());
    po.setStatus(issuance.status());
    po.setIdempotencyKey(issuance.idempotencyKey());
    po.setVersion(issuance.version());
    if (po.getId() == null) {
      po.setCreatedAt(now);
      po.setUpdatedAt(now);
      mapper.insert(po);
    } else {
      po.setUpdatedAt(now);
      mapper.updateById(po);
    }
    return toDomain(po);
  }

  @Override
  public boolean markRedeemed(Long issuanceId, Integer expectedVersion, Long orderId,
                              LocalDateTime redeemedAt) {
    return mapper.update(null, Wrappers.<MktCouponIssuancePo>lambdaUpdate()
        .eq(MktCouponIssuancePo::getId, issuanceId)
        .eq(MktCouponIssuancePo::getStatus, MktCouponIssuance.STATUS_ISSUED)
        .eq(MktCouponIssuancePo::getVersion, expectedVersion)
        .set(MktCouponIssuancePo::getStatus, MktCouponIssuance.STATUS_USED)
        .set(MktCouponIssuancePo::getUsedAt, redeemedAt)
        .set(MktCouponIssuancePo::getOrderId, orderId)
        .set(MktCouponIssuancePo::getVersion, expectedVersion + 1)
        .set(MktCouponIssuancePo::getUpdatedAt, LocalDateTime.now())) == 1;
  }

  private MktCouponIssuance toDomain(MktCouponIssuancePo po) {
    return new MktCouponIssuance(po.getId(), po.getTenantId(), po.getCouponId(), po.getCustomerId(),
        po.getIssuedAt(), po.getUsedAt(), po.getOrderId(), po.getStatus(), po.getIdempotencyKey(),
        po.getVersion());
  }
}

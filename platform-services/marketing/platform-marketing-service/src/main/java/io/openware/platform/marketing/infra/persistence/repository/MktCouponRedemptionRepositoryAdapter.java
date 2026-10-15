package io.openware.platform.marketing.infra.persistence.repository;

import io.openware.platform.marketing.domain.coupon.model.MktCouponRedemption;
import io.openware.platform.marketing.domain.coupon.repository.MktCouponRedemptionRepository;
import io.openware.platform.marketing.infra.persistence.mapper.CouponRedemptionMapper;
import io.openware.platform.marketing.infra.persistence.po.MktCouponRedemptionPo;
import java.time.LocalDateTime;
import org.springframework.stereotype.Repository;

/**
 * {@link MktCouponRedemptionRepository} 的持久化适配器。
 *
 * <p>审计字段由本层填充（created_at/updated_at 为 NOT NULL 无默认值）。重复核销由唯一键
 * uk_mkt_coupon_redemption_issuance 拦截，异常向上抛出由应用层转成业务错误。
 */
@Repository
public class MktCouponRedemptionRepositoryAdapter implements MktCouponRedemptionRepository {

  private final CouponRedemptionMapper mapper;

  public MktCouponRedemptionRepositoryAdapter(CouponRedemptionMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public MktCouponRedemption save(MktCouponRedemption redemption) {
    LocalDateTime now = LocalDateTime.now();
    MktCouponRedemptionPo po = new MktCouponRedemptionPo();
    po.setId(redemption.id());
    po.setTenantId(redemption.tenantId());
    po.setIssuanceId(redemption.issuanceId());
    po.setCouponId(redemption.couponId());
    po.setCustomerId(redemption.customerId());
    po.setOrderId(redemption.orderId());
    po.setAmount(redemption.amount());
    po.setDiscountAmount(redemption.discountAmount());
    po.setRedeemedAt(redemption.redeemedAt());
    po.setVersion(redemption.version());
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    mapper.insert(po);
    return new MktCouponRedemption(po.getId(), po.getTenantId(), po.getIssuanceId(), po.getCouponId(),
        po.getCustomerId(), po.getOrderId(), po.getAmount(), po.getDiscountAmount(),
        po.getRedeemedAt(), po.getVersion());
  }
}

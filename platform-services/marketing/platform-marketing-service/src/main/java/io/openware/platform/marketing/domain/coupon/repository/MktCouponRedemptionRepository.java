package io.openware.platform.marketing.domain.coupon.repository;

import io.openware.platform.marketing.domain.coupon.model.MktCouponRedemption;

/**
 * 优惠券核销记录仓储端口。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型。
 */
public interface MktCouponRedemptionRepository {

  /**
   * 保存核销记录。
   *
   * <p>唯一键 uk_mkt_coupon_redemption_issuance 保证同一发放只核销一次：重复插入会抛
   * {@code DuplicateKeyException}，调用方据此判定重复核销。这是数据库层的兜底，
   * 与 {@link MktCouponIssuanceRepository#markRedeemed} 的条件更新共同构成双重防护。
   */
  MktCouponRedemption save(MktCouponRedemption redemption);
}

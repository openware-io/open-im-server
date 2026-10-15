package io.openware.platform.marketing.domain.coupon.model;

import java.time.LocalDateTime;

/**
 * 优惠券核销记录（领域模型）：一次核销的事实。
 *
 * <p>{@code redeemedAt} 是**业务发生时间**，保留；审计字段不在此承载；{@code version} 保留（并发语义）。
 *
 * @param id             核销标识
 * @param tenantId       归属租户
 * @param issuanceId     对应发放
 * @param couponId       优惠券
 * @param customerId     客户
 * @param orderId        核销订单
 * @param amount         订单金额（最小货币单位整数）
 * @param discountAmount 本次优惠金额（最小货币单位整数）
 * @param redeemedAt     核销时间
 * @param version        版本号
 */
public record MktCouponRedemption(
    Long id,
    Long tenantId,
    Long issuanceId,
    Long couponId,
    Long customerId,
    Long orderId,
    Long amount,
    Long discountAmount,
    LocalDateTime redeemedAt,
    Integer version) {

  /** 新建核销记录：尚未落库，版本初值 0。 */
  public static MktCouponRedemption record(Long tenantId, MktCouponIssuance issuance, Long orderId,
                                           long amount, long discountAmount, LocalDateTime redeemedAt) {
    return new MktCouponRedemption(null, tenantId, issuance.id(), issuance.couponId(),
        issuance.customerId(), orderId, amount, discountAmount, redeemedAt, 0);
  }
}

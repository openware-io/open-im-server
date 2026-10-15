package io.openware.platform.marketing.domain.coupon.model;

import java.time.LocalDateTime;

/**
 * 优惠券发放记录（聚合根）：某张券发给某个客户的可核销凭据。
 *
 * <p>审计字段不在此承载；{@code version} 保留（乐观锁并发语义），{@code issuedAt} / {@code usedAt} /
 * {@code redemptionOrderId} 是业务时间与业务归属，保留。
 *
 * @param id                发放标识
 * @param tenantId          归属租户
 * @param couponId          优惠券
 * @param customerId        客户
 * @param issuedAt          发放时间
 * @param usedAt            核销时间；未核销为空
 * @param redemptionOrderId 核销订单；未核销为空
 * @param status            状态：ISSUED / USED
 * @param idempotencyKey    幂等键
 * @param version           版本号
 */
public record MktCouponIssuance(
    Long id,
    Long tenantId,
    Long couponId,
    Long customerId,
    LocalDateTime issuedAt,
    LocalDateTime usedAt,
    Long redemptionOrderId,
    String status,
    String idempotencyKey,
    Integer version) {

  /** 已发放、待核销。 */
  public static final String STATUS_ISSUED = "ISSUED";

  /** 已核销。 */
  public static final String STATUS_USED = "USED";

  /** 新建发放记录：状态固定为待核销，版本初值为 0，尚未落库。 */
  public static MktCouponIssuance issue(Long tenantId, Long couponId, Long customerId,
                                        String idempotencyKey, LocalDateTime issuedAt) {
    return new MktCouponIssuance(null, tenantId, couponId, customerId, issuedAt, null, null,
        STATUS_ISSUED, idempotencyKey, 0);
  }

  /** 是否可核销：仅待核销状态可核销。 */
  public boolean redeemable() {
    return STATUS_ISSUED.equals(status);
  }

  /** 当前版本；为空时视为 0。 */
  public int currentVersion() {
    return version == null ? 0 : version;
  }
}

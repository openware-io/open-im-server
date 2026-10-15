package io.openware.platform.marketing.domain.coupon.model;

/**
 * 优惠券（聚合根）。
 *
 * <p>金额一律为**最小货币单位整数**（见 SAAS_PLATFORM 金额口径）。审计字段不在此承载：由持久化适配器填充；
 * {@code version} 保留（并发语义）。
 *
 * <p>优惠计算是本聚合的核心行为，收在 {@link #discountFor(long)} 里，而不是散在应用服务中：它同时依赖
 * 优惠类型、面值与封顶，属于同一个不变量集合。
 *
 * @param id               优惠券标识
 * @param tenantId         归属租户（建表为 NOT NULL，无默认值）
 * @param campaignId       所属活动
 * @param discountType     优惠类型
 * @param discountValue    优惠值：固定金额为最小货币单位整数，比例为基点（万分之一）
 * @param minAmount        使用门槛（最小货币单位整数），0 表示无门槛
 * @param maxDiscount      比例优惠的封顶金额，0 表示不封顶
 * @param usageLimit       总发放上限
 * @param perCustomerLimit 单客户发放上限
 * @param status           状态：ACTIVE / 其它
 * @param version          版本号
 */
public record MktCoupon(
    Long id,
    Long tenantId,
    Long campaignId,
    DiscountType discountType,
    Long discountValue,
    Long minAmount,
    Long maxDiscount,
    Integer usageLimit,
    Integer perCustomerLimit,
    String status,
    Integer version) {

  /** 优惠券处于可发放／可核销状态。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /**
   * 创建优惠券：门槛／封顶／发放上限的默认值与版本初值由本工厂给定。
   *
   * <p>回退口径收在这里，避免调用方各自写三元表达式而彼此不一致。
   * {@code tenantId} 为必填：建表该列为 NOT NULL 且无默认值，缺失会让 INSERT 失败。
   */
  public static MktCoupon create(Long tenantId, Long campaignId, DiscountType discountType,
                                 Long discountValue, Long minAmount, Long maxDiscount,
                                 Integer usageLimit, Integer perCustomerLimit, String status) {
    return new MktCoupon(null, tenantId, campaignId, discountType, discountValue,
        minAmount == null ? 0L : minAmount,
        maxDiscount == null ? 0L : maxDiscount,
        usageLimit == null ? 1 : usageLimit,
        perCustomerLimit == null ? 1 : perCustomerLimit,
        status == null || status.isBlank() ? STATUS_ACTIVE : status,
        0);
  }

  /** 是否可发放：非可用状态即拒绝。 */
  public boolean issuable() {
    return STATUS_ACTIVE.equals(status);
  }

  /** 订单金额是否达到使用门槛。 */
  public boolean meetsThreshold(long amount) {
    return minAmount == null || amount >= minAmount;
  }

  /**
   * 计算本次核销的优惠金额。
   *
   * <p>固定金额取面值与订单金额的较小值（优惠不能超过订单本身）；比例优惠按基点折算后应用封顶，
   * 且结果不小于 0。
   */
  public long discountFor(long amount) {
    long value = discountValue == null ? 0L : discountValue;
    if (discountType == null || !discountType.isPercentage()) {
      return Math.min(value, amount);
    }
    long discount = amount * value / 10000L;
    if (maxDiscount != null && maxDiscount > 0) {
      discount = Math.min(discount, maxDiscount);
    }
    return Math.max(discount, 0L);
  }
}

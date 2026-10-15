package io.openware.platform.marketing.domain.coupon.model;

/**
 * 优惠类型。
 *
 * <p>用枚举而不是字符串：取值集合是封闭的（校验里已经穷举），把它建模成类型可以让"未知优惠类型"成为
 * 编译期或构造期问题，而不是散落各处的字符串比较。
 */
public enum DiscountType {

  /** 固定金额减免：{@code discountValue} 为最小货币单位整数。 */
  FIXED_AMOUNT,

  /** 按比例减免：{@code discountValue} 为**基点**（万分之一）。 */
  PERCENTAGE;

  /** 是否按比例减免。 */
  public boolean isPercentage() {
    return this == PERCENTAGE;
  }

  /** 解析取值；未知或为空即拒绝（返回 {@code null} 由调用方给出业务错误）。 */
  public static DiscountType parse(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    for (DiscountType type : values()) {
      if (type.name().equals(value)) {
        return type;
      }
    }
    return null;
  }
}

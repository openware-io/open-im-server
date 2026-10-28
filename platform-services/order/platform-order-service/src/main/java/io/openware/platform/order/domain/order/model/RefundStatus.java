package io.openware.platform.order.domain.order.model;

/**
 * 订单**退款状态**（{@code ord_order.refund_status}）：{@code NONE / PARTIAL / FULL}。
 *
 * <p>退款是交易事实，与业态无关；KTV 的退款（收银台/退款审批）与酒店退款共用同一列口径。
 * 金额口径看 {@code paid_amount / refundable_amount}，本列只表达「有没有退、退到什么程度」。
 */
public enum RefundStatus {
  NONE,
  PARTIAL,
  FULL;

  /** 解析列值；{@code null}/空/未知一律视为 {@link #NONE}（列值异常时的安全默认）。 */
  public static RefundStatus parse(String code) {
    if (code == null || code.isBlank()) {
      return NONE;
    }
    for (RefundStatus status : values()) {
      if (status.name().equalsIgnoreCase(code.trim())) {
        return status;
      }
    }
    return NONE;
  }

  /** 是否发生过退款。 */
  public boolean refunded() {
    return this != NONE;
  }
}

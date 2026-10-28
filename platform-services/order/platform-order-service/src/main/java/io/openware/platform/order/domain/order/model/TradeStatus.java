package io.openware.platform.order.domain.order.model;

/**
 * 订单**交易状态**（{@code ord_order.trade_status}）——订单主表只表达交易事实，不表达业态业务状态。
 *
 * <p>目标契约（{@code docs/40-domain/architecture/ORDER_ROOM_INVENTORY_TARGET.md} §2）：
 * {@code DRAFT / PENDING_PAYMENT / PAID / COMPLETED / CANCELLED / VOIDED}。
 * KTV 的开台/暂停/恢复/结台是 {@code ord_ktv_session.status} 的事实，酒店的在住/待退是
 * {@code htl_booking.status} 的事实，**都不再写进订单状态**；旧 {@code ord_order.status} 兼容列
 * 已在收口迁移中删除，本枚举是订单状态的唯一来源。
 *
 * <p>语义（2026-10 统一，Lead 决策）：
 * <ul>
 *   <li>{@link #DRAFT}：已建单，尚未进入结算（KTV 开台中/未结台）；</li>
 *   <li>{@link #PENDING_PAYMENT}：已进入结算但未全额收款（部分收款也留在这里，金额看
 *       {@code paid_amount}，**不用 PAID 表示部分收款**）；</li>
 *   <li>{@link #PAID}：已全额收款，尚未完成履约/关单；</li>
 *   <li>{@link #COMPLETED}：履约完成且关闭（KTV 结台收口 / 酒店退房完成）；</li>
 *   <li>{@link #CANCELLED}：用户取消，不等同作废；</li>
 *   <li>{@link #VOIDED}：审批作废/冲正，报表与取消分开统计。</li>
 * </ul>
 */
public enum TradeStatus {
  DRAFT,
  PENDING_PAYMENT,
  PAID,
  COMPLETED,
  CANCELLED,
  VOIDED;

  /** 解析列值；{@code null}/空/未知一律视为 {@link #DRAFT}（列值异常时的安全默认）。 */
  public static TradeStatus parse(String code) {
    if (code == null || code.isBlank()) {
      return DRAFT;
    }
    for (TradeStatus status : values()) {
      if (status.name().equalsIgnoreCase(code.trim())) {
        return status;
      }
    }
    return DRAFT;
  }

  /** 终态：不可再加项/取消/结算。 */
  public boolean terminal() {
    return this == COMPLETED || this == CANCELLED || this == VOIDED;
  }

  /** 是否已进入结算（非 DRAFT）。 */
  public boolean settled() {
    return this != DRAFT;
  }

  /** 是否已收款（PAID/COMPLETED）。 */
  public boolean paid() {
    return this == PAID || this == COMPLETED;
  }
}

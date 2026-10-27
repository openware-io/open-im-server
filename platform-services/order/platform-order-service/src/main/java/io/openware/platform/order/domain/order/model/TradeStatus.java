package io.openware.platform.order.domain.order.model;

/**
 * 订单**交易状态**（{@code ord_order.trade_status}）——订单主表只表达交易事实，不表达业态业务状态。
 *
 * <p>目标契约（{@code docs/40-domain/architecture/ORDER_ROOM_INVENTORY_TARGET.md} §2）：
 * {@code DRAFT / PENDING_PAYMENT / PAID / COMPLETED / CANCELLED}。
 * KTV 的开台/暂停/恢复/结台是 {@code ord_ktv_session.status} 的事实，酒店的在住/待退是
 * {@code htl_booking.status} 的事实，**都不再写进订单状态**。收口前 {@code ord_order.status}
 * 仍作为兼容列同步写入（{@link #legacyStatus()}），但代码读取一律以本枚举为准。
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

  /** 解析列值；{@code null}/空/未知一律视为 {@link #DRAFT}（历史行没有该列时的安全默认）。 */
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

  /**
   * 旧 {@code ord_order.status} → 交易状态（只用于**读**历史行/兼容列，不用于新写入）。
   *
   * <p>SERVING/WAITING_ARRIVAL 是「已建单、服务中、尚未结算」→ DRAFT；
   * WAITING_SETTLEMENT/WAITING_PAYMENT 是「已进入结算、未收款」→ PENDING_PAYMENT；
   * REFUNDED/PARTIAL_REFUNDED 交易已完结 → COMPLETED（退款另由 refund_status 表达）。
   */
  public static TradeStatus fromLegacy(String legacyStatus) {
    if (legacyStatus == null || legacyStatus.isBlank()) {
      return DRAFT;
    }
    return switch (legacyStatus.trim().toUpperCase(java.util.Locale.ROOT)) {
      case "COMPLETED", "REFUNDED", "PARTIAL_REFUNDED" -> COMPLETED;
      case "CANCELLED" -> CANCELLED;
      case "VOIDED" -> VOIDED;
      case "WAITING_SETTLEMENT", "WAITING_PAYMENT" -> PENDING_PAYMENT;
      case "PAID" -> PAID;
      default -> DRAFT;
    };
  }

  /** 收口前同步写入 {@code ord_order.status} 的兼容值（最终清理迁移会删掉该列）。 */
  public String legacyStatus() {
    return switch (this) {
      case DRAFT -> "DRAFT";
      case PENDING_PAYMENT -> "WAITING_SETTLEMENT";
      case PAID -> "WAITING_PAYMENT";
      case COMPLETED -> "COMPLETED";
      case CANCELLED -> "CANCELLED";
      case VOIDED -> "VOIDED";
    };
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

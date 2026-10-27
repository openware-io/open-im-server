package io.openware.platform.order.domain.ktv.model;

/**
 * KTV 包厢会话状态（{@code ord_ktv_session.status}）——**KTV 业务状态的唯一事实来源**。
 *
 * <p>目标契约（{@code ORDER_ROOM_INVENTORY_TARGET.md} §4）：
 * {@code RESERVED / OPEN / PAUSED / CLOSED / CANCELLED}。
 * 订单主表不再表达 SERVING / WAITING_SETTLEMENT：开台中/挂单中/已结台只看本枚举。
 *
 * <p>状态机：{@code RESERVED → OPEN ⇄ PAUSED → CLOSED}，异常终结 {@code CANCELLED}。
 */
public enum KtvSessionStatus {
  /** 已预留（未开台）。 */
  RESERVED,
  /** 开台中（计时计费进行中）。 */
  OPEN,
  /** 挂单/暂停中（停表，不计费）。 */
  PAUSED,
  /** 已结台（终结）。 */
  CLOSED,
  /** 已取消（终结）。 */
  CANCELLED;

  /** 解析列值；{@code null}/空/未知返回 {@code null}（调用方按「非法状态」处理，不静默编造）。 */
  public static KtvSessionStatus parseOrNull(String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    for (KtvSessionStatus status : values()) {
      if (status.name().equalsIgnoreCase(code.trim())) {
        return status;
      }
    }
    return null;
  }

  /** 解析列值；未知按 {@link #RESERVED} 兜底（历史行脏数据不应让读路径 500）。 */
  public static KtvSessionStatus parse(String code) {
    KtvSessionStatus parsed = parseOrNull(code);
    return parsed == null ? RESERVED : parsed;
  }

  /** 是否待开台（占用语义是「预占」，取消时用 cancel 而非 release）。 */
  public boolean reserved() {
    return this == RESERVED;
  }

  /** 是否开台中（OPEN/PAUSED）：只有这两个状态的房费还在随时间增长。 */
  public boolean ongoing() {
    return this == OPEN || this == PAUSED;
  }

  /** 是否仍占用包厢（RESERVED/OPEN/PAUSED）：订单取消时这三种状态都要释放占用。 */
  public boolean active() {
    return this == RESERVED || this == OPEN || this == PAUSED;
  }

  /** 是否终态（CLOSED/CANCELLED）。 */
  public boolean terminal() {
    return this == CLOSED || this == CANCELLED;
  }
}

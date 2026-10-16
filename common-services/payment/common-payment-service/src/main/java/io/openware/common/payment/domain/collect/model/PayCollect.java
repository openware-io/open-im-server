package io.openware.common.payment.domain.collect.model;

import java.time.LocalDateTime;

/**
 * 收款单（领域模型）：组合收款的状态机载体与幂等记录。
 *
 * <p>{@code requestJson}/{@code responseJson} 是**原始快照**，由调用方负责序列化：
 * 请求快照记下当时的应付与分腿，结果快照记下确认结果或失败原因。领域模型只保证它们被原样存取，
 * 不解释其结构——快照结构属于用例口径，且读模型必须容忍历史快照缺字段。
 *
 * <p><b>两个时间字段语义不同</b>：{@code createdAt} 是收款单建立时刻；
 * {@code updatedAt} 由写入方在每次状态迁移时刷新，用于排查"这条记录卡在 INIT 多久了"。
 *
 * <p><b>状态迁移**不可变**</b>：{@link #withState} 返回新实例而不是原地改字段。
 * 这让"已确认的结果快照"不会因为后续某次失败写回而被就地覆盖，
 * 也让调用方必须显式把新状态交给端口，无法靠共享引用"顺手"改变已提交的事实。
 *
 * @param id             收款单标识
 * @param tenantId       租户
 * @param storeId        门店
 * @param businessType   业态
 * @param collectNo      收款单号（对外标识，事件与审计按它关联）
 * @param orderId        订单
 * @param idempotencyKey 组合收款幂等键（唯一键 {@code uk_pay_collect_idem} 的依据）
 * @param state          状态：{@code INIT} / {@code CONFIRMED} / {@code FAILED}
 * @param currencyCode   币种快照
 * @param requestJson    请求快照（JSON 原文）
 * @param responseJson   结果快照或失败快照（JSON 原文）
 * @param createdAt      建立时刻
 * @param updatedAt      最近一次状态迁移时刻
 */
public record PayCollect(
    Long id,
    Long tenantId,
    Long storeId,
    String businessType,
    String collectNo,
    Long orderId,
    String idempotencyKey,
    String state,
    String currencyCode,
    String requestJson,
    String responseJson,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {

  /** 已建立、尚未确认：可能正在处理，也可能是进程中断留下的悬空记录。 */
  public static final String STATE_INIT = "INIT";

  /** 已确认收款（钱确实收到）。 */
  public static final String STATE_CONFIRMED = "CONFIRMED";

  /** 已失败（本地已回滚，快照里带补偿结果，供同键重跑续做）。 */
  public static final String STATE_FAILED = "FAILED";

  /** 是否已确认。 */
  public boolean confirmed() {
    return STATE_CONFIRMED.equals(state);
  }

  /** 是否处于可被同键重跑接管的状态。 */
  public boolean failed() {
    return STATE_FAILED.equals(state);
  }

  /** 是否已有可回放的确认结果。 */
  public boolean replayable() {
    return confirmed() && responseJson != null && !responseJson.isBlank();
  }

  /**
   * 状态迁移：返回带新状态与快照的实例。
   *
   * @param newState         目标状态
   * @param newResponseJson  新的结果/失败快照；{@code null} 表示清空（重跑接管时用）
   */
  public PayCollect withState(String newState, String newResponseJson) {
    return new PayCollect(id, tenantId, storeId, businessType, collectNo, orderId, idempotencyKey,
        newState, currencyCode, requestJson, newResponseJson, createdAt, LocalDateTime.now());
  }

  /**
   * 重跑接管后的形态：状态回到 {@code INIT}，并**清空旧失败快照**。
   *
   * <p>清空是必要的：留着旧快照会让后续读方把一次进行中的重跑误判成"已失败（带补偿结果）"，
   * 从而在同一次重跑里再次尝试续做补偿。
   */
  public PayCollect claimedForRetry() {
    return withState(STATE_INIT, null);
  }
}

package io.openware.common.payment.domain.collect.model;

/**
 * 收款单（领域模型）。
 *
 * <p>只保留读模型需要的字段：本域目前对收款单只有"按订单回看收款明细"这一只读用途，
 * 把整张表的列都搬进领域层会造出一个没人使用的宽模型。
 *
 * <p>{@code requestJson}/{@code responseJson} 是**原始快照**（请求时的应付金额与结果时的分腿），
 * 不在此解析：读模型要能容忍历史快照缺字段，解析失败只跳过该条；严格解析属于写路径。
 *
 * @param id           收款单标识
 * @param tenantId     租户
 * @param storeId      门店
 * @param orderId      订单
 * @param collectNo    收款单号
 * @param state        状态：CONFIRMED 表示钱确实收到
 * @param currencyCode 币种快照
 * @param requestJson  请求快照（JSON 原文）
 * @param responseJson 结果快照（JSON 原文）
 */
public record PayCollect(
    Long id,
    Long tenantId,
    Long storeId,
    Long orderId,
    String collectNo,
    String state,
    String currencyCode,
    String requestJson,
    String responseJson,
    java.time.LocalDateTime createdAt) {

  /** 已确认收款（钱确实收到）。 */
  public static final String STATE_CONFIRMED = "CONFIRMED";
}

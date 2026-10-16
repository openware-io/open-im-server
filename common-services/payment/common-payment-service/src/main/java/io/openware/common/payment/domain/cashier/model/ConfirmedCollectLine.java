package io.openware.common.payment.domain.cashier.model;

/**
 * 一笔已确认组合收款（日结按支付构成统计的输入）。
 *
 * <p>按 standards/10，它是**具名投影类型**：由跨表查询派生（{@code pay_collect} 经
 * {@code ord_order} 定位门店），字段集不等于任何单张表。
 *
 * <p><b>为什么需要这条读法</b>：储值币（WALLET）与积分（POINT）这两种**支付工具**的抵扣不落
 * {@code pay_intent}——现金类分腿才写流水，跨域的储值/积分扣减落在 customer 域账本，
 * 本服务内只剩 {@code response_json} 的分腿记录。要按支付构成口径统计这类抵扣，必须回读这里。
 *
 * <p>{@code responseJson} 保持原文：分腿结构属于展示/统计口径，由调用方按容忍缺失的方式解析
 * （历史快照可能缺字段，解析失败只跳过该条）。
 *
 * @param currencyCode 币种（收款单快照优先，为空的历史行回退订单币种）
 * @param responseJson 组合收款结果快照（含 {@code collectedByMethod} 分腿）
 */
public record ConfirmedCollectLine(
    String currencyCode,
    String responseJson) {
}

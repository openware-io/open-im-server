package io.openware.common.payment.domain.cashier.model;

import java.math.BigDecimal;

/**
 * 退款按币种聚合（交班/日结长短款汇总用）。
 *
 * <p>按 standards/10“领域模型与只读投影的边界”，它是**具名投影类型**：由一条跨表聚合查询派生
 * （退款单币种优先、缺省回退订单币种），字段集不等于任何单张表。
 *
 * <p>金额口径：{@code COALESCE(approved_amount, requested_amount, 0)}——已批准金额优先，
 * 未批准时用申请金额，都没有则为 0。这个回退顺序收在适配器的 SQL 里，领域侧只消费结果。
 *
 * @param currencyCode  币种（已归一）
 * @param refundCount   退款笔数
 * @param refundAmount  退款金额（最小货币单位）
 */
public record RefundCurrencyAggregate(
    String currencyCode,
    long refundCount,
    BigDecimal refundAmount) {
}

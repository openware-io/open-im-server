package io.openware.common.payment.application;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单收款明细（供订单管理 / 收银台把「组合支付到底怎么收的」一次看全）。
 *
 * <p><b>为什么单独开一个读模型</b>：订单账单（{@code BillResult.Collected}）只汇总
 * 「现金 / A380币 / 积分」三个桶，线上渠道（支付宝/微信/Stripe）被并进现金桶，
 * 同一订单的多次收款、每次收款的分腿构成、渠道流水号与退款也都看不到——运营在
 * 「订单管理」里核对组合支付时会缺信息。本模型按**收款单**展开：
 * <ul>
 *   <li>{@code collections}：每一笔已确认收款（收款单号 / 币种 / 本次应收 / 收款时间 / 会员），
 *       以及该笔的**全部分腿**（积分、储值、现金、支付宝、微信、Stripe 各自一行，不合并、不省略）；
 *       {@code combined=true} 表示该笔为组合支付（分腿 &gt; 1）。</li>
 *   <li>{@code transactions}：现金类分腿对应的支付流水（渠道、渠道交易号、状态、发生时间），
 *       线上渠道对账要用的就是这一层。</li>
 *   <li>{@code refunds}：该订单的退款记录（含状态与退款单号）。</li>
 * </ul>
 *
 * <p><b>金额口径</b>：一律最小货币单位整数（与全仓一致），由服务端从落库的
 * {@code BigDecimal} 归一，客户端不得再换算。
 *
 * <p><b>币种口径</b>（16_CURRENCY_CONVENTIONS §5）：{@code currencyCode} 只在命中数据币种一致时给出；
 * 混币种时 {@code currencyCode=null} 且 {@code mixedCurrency=true}，调用方必须按标记处理，
 * <b>禁止</b>把不同币种的分腿相加展示。
 *
 * <p><b>只包含已确认收款</b>：{@code pay_collect.state=CONFIRMED}（钱确实收到了）。
 * INIT/HOLD/FAILED 的收款尝试不是收款明细，不在此模型内（失败留痕走审计与收款接口错误码）。
 */
public record OrderCollectionsDto(Long orderId,
                                  String currencyCode,
                                  boolean mixedCurrency,
                                  long totalCollected,
                                  long refundedAmount,
                                  List<Collection> collections,
                                  List<Transaction> transactions,
                                  List<Refund> refunds) {

    /** 一笔组合收款（{@code combined=true} 即多分腿）。{@code payable} 为该次收款的应收金额。 */
    public record Collection(String collectNo,
                             String currencyCode,
                             long payable,
                             LocalDateTime collectedAt,
                             Long customerId,
                             boolean combined,
                             List<Leg> legs) {}

    /** 一条收款分腿：{@code method} ∈ {POINT, WALLET, CASH, ALIPAY, WECHAT, STRIPE}。 */
    public record Leg(String method, long amount) {}

    /** 一条渠道支付流水（现金/线上分腿的落库流水；线上渠道的 {@code providerTransactionNo} 用于对账）。 */
    public record Transaction(Long id,
                              String provider,
                              String providerTransactionNo,
                              long amount,
                              String currencyCode,
                              String status,
                              LocalDateTime occurredAt) {}

    /**
     * 一笔退款：状态 ∈ {PENDING, APPROVED, REJECTED, REFUNDED}；
     * {@code approvedAmount} 为 0 表示尚未批准，退款金额看 {@code requestedAmount}。
     */
    public record Refund(Long id,
                         String status,
                         long requestedAmount,
                         long approvedAmount,
                         String currencyCode,
                         String providerRefundNo,
                         String reason,
                         LocalDateTime createdAt,
                         LocalDateTime updatedAt) {}

    /** 落库的 {@code BigDecimal} 金额归一为最小货币单位整数；向下取整避免历史脏数据抛异常。 */
    static long minor(BigDecimal value) {
        return value == null ? 0L : value.setScale(0, java.math.RoundingMode.DOWN).longValueExact();
    }
}

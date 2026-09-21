package com.gvchat.common.payment.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * 营业日结汇总（{@code pay_daily_closing.summary_json} 的唯一结构定义）：**按币种分组**落库，
 * 禁止跨币种静默求和（{@code docs/standards/16_CURRENCY_CONVENTIONS.md} §5/§6）。
 *
 * <p><b>为什么按币种分组</b>：同一门店同一营业日可能同时存在 CNY 与 USD 的收款 / 退款 / 交班，
 * 把两种币种相加会得出没有业务含义的「收款总额」和「长短款」。因此金额只出现在
 * {@link CurrencyLine} 里，顶层只给单币种 {@code currencyCode} 与 {@code mixedCurrency} 标记
 * （与 {@link ReconciliationApplicationService} 的信封口径一致：混币种时 {@code currencyCode=null}，
 * 调用方必须按行内 {@code currencyCode} 分别展示）。
 *
 * <p><b>金额单位（冻结）</b>：一律<b>最小货币单位整数</b>（CNY 分 / USD cent），与
 * {@code pay_intent.amount}、{@code pay_shift.opening_cash/expected_cash/actual_cash/difference_amount}
 * 同量级；这里以 {@code long} 落库，<b>不做任何分/元换算，也不做任何汇率换算</b>。
 *
 * <p><b>结构稳定性</b>：没有任何收款 / 退款 / 交班的空营业日也会产出一条全 0 的 {@link CurrencyLine}
 * （币种取当时租户币种），因此 {@code currencies} 至少一条、{@code summary_json} 不为 {@code null}，
 * 调用方无需处理空数组或 null 汇总。
 *
 * @param businessDate  营业日（ISO-8601 日期，与 {@code pay_daily_closing.business_date} 同值）
 * @param storeId       门店 ID（汇总范围＝单门店，不跨门店）
 * @param currencyCode  单币种时＝该币种；混币种（{@code mixedCurrency=true}）时为 {@code null}
 * @param mixedCurrency 是否出现多种币种（true 时调用方必须按 {@code currencies[].currencyCode} 分别展示）
 * @param currencies    按币种代码升序的分组明细（至少一条，空营业日为一条全 0 分组）
 */
public record DailyClosingSummary(String businessDate, Long storeId, String currencyCode, boolean mixedCurrency,
                                  List<CurrencyLine> currencies) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * 单币种汇总行。金额只对本行 {@code currencyCode} 有效，跨行不得相加。
     *
     * @param currencyCode           本行币种快照（来自 pay_intent / pay_collect / pay_refund / pay_shift 的币种列）
     * @param collectionCount        本币种成功收款笔数（pay_intent 成功行数，与对账同源）
     * @param collectedAmount        本币种收款总额（**只含现金类渠道**，来自 pay_intent；最小货币单位整数）。
     *                               储值币/积分抵扣的账单金额不并入本字段，而是以 {@code providers[]} 里的
     *                               {@code WALLET}/{@code POINT} 独立行并列展示，因此
     *                               「支付构成 = collectedAmount + 储值币抵扣金额 + 积分抵扣金额」；
     *                               把 {@code providers[]} 全部相加会得到不同的口径，不可混用。
     *                               注意：储值币/积分是支付工具而非货币，它们的**数量**不参与任何货币合计
     *                               （本汇总也不输出数量，原因见 {@link ProviderLine}）。
     * @param cashCount              本币种现金收款笔数（provider=CASH）
     * @param cashAmount             本币种现金收取合计（provider=CASH；与交班现金对账同源同窗口语义）
     * @param refundCount            本币种已退款笔数（pay_refund 终态 REFUNDED）
     * @param refundAmount           本币种退款合计（approved_amount 缺失时回退 requested_amount）
     * @param shiftCount             本币种已交班班次数（pay_shift CLOSED/REVIEW_REQUIRED）
     * @param shiftDifferenceAmount  本币种交班长短款合计（pay_shift.difference_amount 同币种求和，可为负＝短款）
     * @param providers              本币种按支付方式/渠道的细分（provider 升序）：
     *                               现金类渠道来自 pay_intent；{@code WALLET}/{@code POINT} 为
     *                               组合收款分腿里储值币/积分**抵扣的账单金额**独立分项
     *                               （只对新增数据生效，不回填历史；数量类字段不在本结构内）。
     */
    public record CurrencyLine(String currencyCode, long collectionCount, long collectedAmount, long cashCount,
                               long cashAmount, long refundCount, long refundAmount, long shiftCount,
                               long shiftDifferenceAmount, List<ProviderLine> providers) {}

    /**
     * 支付方式/渠道细分行。
     *
     * <p><b>现金类渠道</b>（{@code CASH/ALIPAY/WECHAT/STRIPE}）来自 {@code pay_intent.provider}
     * （对账口径，组合收款写入时 provider = payment_method），其 {@code amount} 是**货币金额**且已包含在
     * {@link CurrencyLine#collectedAmount()} 里。
     *
     * <p><b>储值币 / 积分</b>（{@code WALLET} / {@code POINT}）来自 {@code pay_collect.response_json}
     * 的分腿记录，是**组合支付里的一种支付工具**（不是货币），其 {@code amount} 是
     * <b>该腿抵扣的账单金额</b>（货币，最小货币单位整数，随本次收款的币种快照归组），
     * 可与现金合计一起构成「支付构成」；它**不在** {@code collectedAmount}/{@code cashAmount} 内，
     * 是本改动新增的独立分项。
     *
     * <p><b>为什么不输出代币/积分数量</b>：{@code pay_collect.response_json} 的每条分腿只有
     * {@code method} + 一个数值，**没有**独立的「代币/积分数量」字段（储值/积分的跨域扣减数量在
     * customer 域账本）。因此这里只输出确实拿到的**抵扣账单金额**，绝不用比例反推、也绝不把数量
     * 当货币金额输出。若将来落库了独立的数量字段，应新增不带 {@code Amount} 字样的字段
     * （如 {@code walletTokens}/{@code points}），并且**不带** {@code currencyCode}、
     * <b>不参与任何货币合计</b>。
     *
     * @param provider 支付方式/渠道/支付工具代码（CASH/ALIPAY/WECHAT/STRIPE，或 WALLET/POINT；
     *                 脏数据为空时归一为 UNKNOWN）
     * @param count    笔数
     * @param amount   金额（最小货币单位整数）：现金类渠道为收款额，WALLET/POINT 为抵扣的账单金额
     */
    public record ProviderLine(String provider, long count, long amount) {}

    /** 序列化落库（{@code pay_daily_closing.summary_json}）；失败即抛，绝不落半截结构。 */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化日结汇总", ex);
        }
    }

    /**
     * 反序列化 {@code summary_json}。
     *
     * <p>历史行（本改动之前 summary_json 从不写入）与损坏/未知结构一律返回 {@code null} 而不是抛错：
     * 日结列表接口不能因为一行历史数据整体 500（同 {@code CollectApplicationService#readFailure} 的降级口径）。
     * 未知字段（未来版本新增）被忽略，保证旧代码读新数据不炸。
     */
    public static DailyClosingSummary parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, DailyClosingSummary.class);
        } catch (Exception ex) {
            return null;
        }
    }
}

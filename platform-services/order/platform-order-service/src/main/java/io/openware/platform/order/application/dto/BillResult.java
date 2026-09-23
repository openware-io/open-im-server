package io.openware.platform.order.application.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 客户消费账单（KTV_BUSINESS_01 §9）：分级明细，金额一律最小货币单位整数 + 币种字符串，服务端计算。
 *
 * <p><b>可解释性</b>：账单不能只给一个总数——每个数字都要能自证来源。为此除了金额，还下发
 * 「怎么算出来的」所需字段：包厢费的分项/块数/计费时长/暂停/标准时长/超时倍率/递增粒度/计价方案名，
 * 以及合计的构成（{@code subtotalAmount} 原价合计 − {@code discountAmount} 优惠 + {@code taxAmount} 税额
 * = {@code totalAmount} 合计）。**客户端不做任何金额算术**，只做展示与拼文案。
 */
public record BillResult(
        String status,
        String currencyCode,
        RoomFee roomFee,
        List<ItemLine> items,
        List<ServerLine> servers,
        List<PromotionLine> promotions,
        // 原价合计（包厢费 + 加项 + 服务人员费，未减优惠、未加税）；合计 = 原价合计 − 优惠 + 税。
        long subtotalAmount,
        // 优惠合计（等于 promotions 各行金额之和；无优惠为 0，且 promotions 为空）。
        long discountAmount,
        // 税额（当前计价方案未启用税费，恒为订单快照值）。
        long taxAmount,
        long totalAmount,
        long paidAmount,
        long payableAmount,
        Collected collected,
        long changeAmount) {

    /**
     * 1 包厢费（含 1 名标准服务人员）：名称/时段/单价/时长/金额 + 计费口径说明。
     * {@code name} 由后端按固化口径给出（新口径「包厢费（含 1 名服务人员）」，历史账单保持「包厢计时费」），
     * 页面直接展示，不得各自硬编码。
     *
     * <p>可解释字段的口径（全部服务端算好，客户端只展示）：
     * <ul>
     *   <li>{@code durationSeconds} 计费时长 D（已扣暂停 {@code pausedSeconds}）；</li>
     *   <li>{@code unitPrice} 每递增粒度单价 p（{@code incrementMinutes} 分钟一档）；</li>
     *   <li>{@code quantity} 计费块数 n = 时段内块数 + 超时块数；</li>
     *   <li>{@code standardSeconds} 标准时长 S，{@code inSeconds}/{@code overSeconds} 时段内/超时秒数，
     *       {@code overtimeRate} 超时倍率（十进制文本）——超时部分单独说明用；</li>
     *   <li>{@code planName} 本次计费使用的计价方案名（房型名或「门店标准价」）；
     *       {@code roomUnitPrice}/{@code serverUnitPrice} 每计费单位分项，{@code roomFeeIncludesServer}
     *       标记包厢费是否已含 1 名标准服务人员（解释「为什么首名服务人员不另计费」）；</li>
     *   <li>{@code live} 是否**实时值**（会话仍在开台中，金额随计费时长增长，未固化）；</li>
     *   <li>{@code source} 这一行金额的来源，决定页面怎么解释它：
     *       {@code LIVE}＝开台中实时估算；{@code CLOSED}＝结台固化（{@code periodEnd} 即结台时刻，
     *       时长可信）；{@code HISTORICAL}＝历史固化明细（会话已终结/已取消但**没有结台时刻**，
     *       时长不可知——此时 {@code durationKnown=false}，页面必须说「未记录结台时刻」而不是「0 分钟」，
     *       金额用 {@code quantity} × {@code unitPrice} 与 {@code snapshotAt} 自证）；</li>
     *   <li>{@code durationKnown} 计费时长是否可信（只有 {@code HISTORICAL} 为 false）；</li>
     *   <li>{@code snapshotAt} 固化金额的生成时刻（明细最后写入时间；实时值为 null）——历史金额的唯一时间锚点。</li>
     * </ul>
     *
     * <p>恒等式（账单自身必须自洽）：{@code sum(items) + sum(servers) + roomFee.amount == totalAmount}。
     */
    public record RoomFee(String name, String periodStart, String periodEnd, long unitPrice,
                          long durationSeconds, long amount, long quantity, long pausedSeconds,
                          long standardSeconds, long inSeconds, long overSeconds, int incrementMinutes,
                          String overtimeRate, String billingUnit, String planName,
                          long roomUnitPrice, long serverUnitPrice, boolean roomFeeIncludesServer,
                          boolean live, String source, boolean durationKnown, String snapshotAt) {}

    /** 2 加项：商品/单价/数量/金额（数量与单价都下发，页面直接展示「单价 × 数量 = 金额」）。 */
    public record ItemLine(String name, long unitPrice, BigDecimal quantity, long amount) {}

    /**
     * 3 服务人员费：人员/时长/单价/块数/金额；包厢费已含的首名服务人员金额为 0 且名称标注免收原因，
     * {@code quantity} 为按同一舍入算法算出的计费块数（与金额同源，便于「单价 × 块数」对账）。
     */
    public record ServerLine(String serverName, long durationSeconds, long unitPrice, long amount, long quantity) {}

    /** 4 优惠逐项：type ∈ {COUPON/DISCOUNT/FULL_REDUCTION/MEMBER_PRICE}。 */
    public record PromotionLine(String type, long amount) {}

    /** 6 已收分项：现金（含线上渠道）/A380币/积分，来自 payment 域组合收款分腿；读不到时退回「已收合计记现金」。 */
    public record Collected(long cash, long wallet, long points) {}
}

package io.openware.platform.order.domain.ktv.service;

import io.openware.platform.order.domain.ktv.model.KtvPricingPlan;
import io.openware.platform.order.domain.ktv.model.KtvRoundingDirection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 包厢计时费计算（KTV_BUSINESS_01 §2.2 / §5.3，纯函数无副作用）。
 *
 * <p>计费口径（与页面文案「¥100.00/小时 · 30 分钟递增」一致）：
 * <ol>
 *   <li>计费时长 D = (closed_at − billing_start_at) − paused_seconds，D ≥ 0；</li>
 *   <li>超时部分 D_over = max(0, D − S)，时段内 D_in = D − D_over；</li>
 *   <li>块数 n(x) = 舍入方向(秒→分钟) 再 (分钟→块)，递增粒度取方案的 {@code increment_minutes}；</li>
 *   <li>每递增粒度单价 p = **结台房费基数**（{@link KtvPricingPlan#billableRoomPricePerIncrement()}：新口径为
 *       「房型 + 服务」的每递增粒度合计，历史快照为房型递增价；递增分钟 × 计费单位分钟换算，整数截断）；</li>
 *   <li>计时费 = p × n(D_in) + p × r × n(D_over)，最后按币种最小单位四舍五入。</li>
 * </ol>
 *
 * <p>结台房费基数已含 1 名标准服务人员（{@code roomFeeIncludesServer}），因此阶梯/取整/超时倍率等规则
 * 全部作用在该基数上，服务费不再单列进房费；第 2 名起的服务人员费由各自会话按
 * {@code price_per_inc} 另行计费（见 {@code KtvServerSessionApplicationService}）。
 *
 * <p>默认舍入方向 {@code CONSUMER_FAVOR}（让利消费者）：秒级零头向下抹掉，不足一个递增块按一个块计，
 * 因此 33 秒 → 0 分钟 → 0 块 → 0 元，而不可能按整小时收 1 小时的钱。
 *
 * <p>结台落库、开台中的实时预估、账单回退计算三条路径必须共用本类，禁止任何一处自行实现计时费公式。
 */
public final class KtvRoomFeeCalculator {
    private KtvRoomFeeCalculator() {}

    /**
     * 计时费计算结果（金额一律最小货币单位整数）。
     *
     * @param billableSeconds 计费时长 D（秒）
     * @param inSeconds       时段内时长 D_in
     * @param overSeconds     超时时长 D_over
     * @param units           计费块数 n(D_in) + n(D_over)（写入 ROOM_FEE 明细 quantity）
     * @param unitPriceMinor  每递增粒度单价 p（写入 ROOM_FEE 明细 unit_price；无超时加价时 单价 × 数量 = 金额）
     * @param amountMinor     计时费（写入 ROOM_FEE 明细 total_amount，是唯一的权威金额；订单合计按明细金额求和）
     */
    public record Fee(long billableSeconds, long inSeconds, long overSeconds,
                      long units, long unitPriceMinor, long amountMinor) {
        /** 全零结果（套餐或 0 时长）。 */
        public static final Fee ZERO = new Fee(0L, 0L, 0L, 0L, 0L, 0L);
    }

    /** 是否按时长递增计费（PACKAGE 一口价套餐不走本公式）。 */
    public static boolean durationBillable(KtvPricingPlan plan) {
        return plan != null && plan.durationBillable();
    }

    /** 计费时长秒（已扣除暂停，不小于 0）。 */
    public static long billableSeconds(LocalDateTime billingStartAt, LocalDateTime closedAt, int pausedSeconds) {
        if (billingStartAt == null || closedAt == null) {
            return 0L;
        }
        long d = Duration.between(billingStartAt, closedAt).getSeconds() - pausedSeconds;
        return Math.max(0L, d);
    }

    /** 标准时长秒 S：有预订取 reserved_end_at − billing_start_at，否则 default_session_minutes × 60。 */
    public static long standardSeconds(LocalDateTime billingStartAt, LocalDateTime reservedEndAt, int defaultSessionMinutes) {
        if (reservedEndAt != null && billingStartAt != null && reservedEndAt.isAfter(billingStartAt)) {
            return Duration.between(billingStartAt, reservedEndAt).getSeconds();
        }
        return (long) Math.max(0, defaultSessionMinutes) * 60L;
    }

    /** 按计价方案与计费时长计算计时费（超时加价只作用于超时部分）。 */
    public static Fee calculate(KtvPricingPlan plan, long billableSeconds, long standardSeconds) {
        long billable = Math.max(0L, billableSeconds);
        long standard = Math.max(0L, standardSeconds);
        long over = Math.max(0L, billable - standard);
        long in = billable - over;
        if (!durationBillable(plan)) {
            return new Fee(billable, in, over, 0L, 0L, 0L);
        }
        long perIncrement = plan.billableRoomPricePerIncrement();
        KtvRoundingDirection direction = plan.effectiveRoundingDirection();
        int incrementMinutes = plan.effectiveIncrementMinutes();
        long inBlocks = direction.secondsToBlocks(in, incrementMinutes);
        long overBlocks = direction.secondsToBlocks(over, incrementMinutes);
        BigDecimal fee = BigDecimal.valueOf(perIncrement).multiply(BigDecimal.valueOf(inBlocks))
                .add(BigDecimal.valueOf(perIncrement).multiply(plan.effectiveOvertimeRate()).multiply(BigDecimal.valueOf(overBlocks)));
        return new Fee(billable, in, over, inBlocks + overBlocks, perIncrement,
                fee.setScale(0, RoundingMode.HALF_UP).longValueExact());
    }
}

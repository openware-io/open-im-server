package com.gvchat.infrastructure.time;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;

/**
 * 报表**营业日 / 统计桶**的唯一计算实现（全仓一份，报表实现不得各自再写一套周/月算法）。
 *
 * <h2>为什么需要它</h2>
 * <p>业务时间列（{@code ord_order.created_at}、{@code pay_transaction.occurred_at}、
 * {@code pay_refund.created_at}、{@code res_occupation.start_at}）落库的是 **UTC 墙钟**
 * （容器 {@code TZ=UTC}、JDBC {@code serverTimezone=UTC}，见
 * {@code docs/renovation/MULTI_TIMEZONE_DESIGN.md} §1.1），而门店按本地时区 + **营业日切点**经营
 * （KTV 通宵场次归前一营业日）。直接拿存储值切日，会把门店凌晨的交易算进错误的营业日
 * （同文档 §1.2 场景 5）。因此：
 *
 * <ul>
 *   <li><b>营业日</b> = {@code (存储值 + 时区偏移 − 营业日切点).toLocalDate()}，
 *       等价于 {@link StoreTimeService#businessDate(LocalDateTime, ZoneId, LocalTime)}
 *       —— 后者是本仓「营业日」的权威实现（含 DST 语义），本类只是把它**在固定偏移下**化成
 *       一个可下推到 SQL 的常量，{@code ReportTimeBucketsTest} 用覆盖全天的采样逐点比对两者，
 *       保证不会出现两套口径；</li>
 *   <li><b>取数窗口</b> = 营业日 {@code [from, to]} 对应的存储区间
 *       {@code [from 00:00 − shift, (to+1) 00:00 − shift)}，与营业日桶严格对齐，
 *       不会出现「首尾各带半天、还多出一个不存在的桶」；</li>
 *   <li><b>桶边界</b>：周 = ISO 周（**周一起、周日止**，跨月/跨年时周标签随 ISO 周所属年，
 *       例如 {@code 2025-12-29} 是 2026 年第 1 周）；月 = 自然月；年 = 自然年。</li>
 * </ul>
 *
 * <h2>SQL 侧如何配合（重要）</h2>
 * <p>营业日切分**必须下推到 SQL 的分组键**：一次 GROUP BY 出来的「UTC 自然日」聚合无法在 Java 里
 * 无损拆成两个营业日（营业日与 UTC 自然日相差 {@code shift}，边界不对齐）。因此 Mapper 一律写
 * <pre>{@code DATE_FORMAT(DATE_ADD(<时间列>, INTERVAL #{businessDayShiftSeconds} SECOND), '%Y-%m-%d')}</pre>
 * 并把 {@link #BUSINESS_DAY_SHIFT_SECONDS} 作为参数传入 —— 偏移量只有这一处定义，
 * Java 与 SQL 不可能各说各话（{@code ReportMapperBusinessDayContractTest} 读注解 SQL 守住这一点）。
 *
 * <h2>已知边界（不得当成已解决）</h2>
 * <p>偏移按**平台默认门店口径**（{@link StoreTimeService#DEFAULT_TIMEZONE} +
 * {@link StoreTimeService#DEFAULT_BUSINESS_DAY_CUTOFF}）计算，**没有**逐门店读
 * {@code tnt_store.timezone}/{@code business_day_cutoff}：MySQL 的 {@code CONVERT_TZ} 用 IANA 名字
 * 依赖时区表（未加载时静默返回 NULL），而把每行门店时区带进 SQL 也会让分组键失效。
 * 因此非默认时区/切点的门店，其营业日边界目前仍按平台默认近似；
 * 一旦出现这类门店，应改为「按门店分组 + 应用层逐门店换算窗口」（见 MULTI_TIMEZONE_DESIGN §7）。
 */
public final class ReportTimeBuckets {

    /** 报表营业日所用的门店时区（平台默认，与 {@link StoreTimeService} 同源）。 */
    public static final String REPORT_TIMEZONE = StoreTimeService.DEFAULT_TIMEZONE;

    /** 报表营业日所用的营业日切点（平台默认：KTV 通宵场次归前一营业日）。 */
    public static final LocalTime REPORT_BUSINESS_DAY_CUTOFF = StoreTimeService.DEFAULT_BUSINESS_DAY_CUTOFF;

    /**
     * 营业日平移量（秒）= 时区偏移 − 营业日切点；{@code 营业日 = (存储值 + 该平移量).toLocalDate()}。
     *
     * <p>以平台默认（{@code Asia/Shanghai} = +08:00，切点 {@code 04:00}）为例 = 8h − 4h = 4h：
     * 存储值 {@code 2026-09-19T18:00}（门店 {@code 09-20 02:00}，通宵）→ 营业日 {@code 2026-09-19}。
     */
    public static final int BUSINESS_DAY_SHIFT_SECONDS = computeShiftSeconds();

    private ReportTimeBuckets() {
    }

    /**
     * 计算平移量：固定偏移（本仓门店时区无 DST）减去营业日切点。
     *
     * <p>用「当前时刻」求偏移而不是写死 +8：门店时区一旦换成有 DST 的区域，这里会立刻变成
     * 「按今天的偏移近似」而不是静默沿用 +8——近似仍旧是近似，但至少不会假装精确。
     */
    private static int computeShiftSeconds() {
        int zoneSeconds = ZoneId.of(REPORT_TIMEZONE).getRules().getOffset(java.time.Instant.now()).getTotalSeconds();
        return zoneSeconds - REPORT_BUSINESS_DAY_CUTOFF.toSecondOfDay();
    }

    /**
     * 存储值（UTC 墙钟）→ 归属的**营业日**（门店本地日 + 营业日切点）。
     *
     * <p>与 {@link StoreTimeService#businessDate(LocalDateTime, ZoneId, LocalTime)} 在平台默认口径下等价，
     * 但这里走固定偏移，便于同一个表达式下推到 SQL 的 GROUP BY。
     *
     * @param storedUtc 业务时间列的存储值，非空
     */
    public static LocalDate businessDay(LocalDateTime storedUtc) {
        return storedUtc.plusSeconds(BUSINESS_DAY_SHIFT_SECONDS).toLocalDate();
    }

    /** 当前营业日：以 UTC 当前时刻换算，不依赖 JVM 默认时区（容器可能是 UTC 也可能是别的）。 */
    public static LocalDate businessToday() {
        return businessDay(LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 营业日窗口下界（含）：营业日 {@code day} 的起点（门店本地 {@code 切点}）对应的**存储值**。
     * 例（平台默认）：营业日 2026-09-19 → 存储 2026-09-18T20:00（门店 2026-09-19 04:00）。
     */
    public static LocalDateTime windowFrom(LocalDate day) {
        return day.atStartOfDay().minusSeconds(BUSINESS_DAY_SHIFT_SECONDS);
    }

    /**
     * 营业日窗口上界（**排他**）：营业日次日起点对应的存储值。
     * SQL 一律写 {@code col >= from AND col < to}，保证半开区间且与营业日桶边界重合。
     */
    public static LocalDateTime windowToExclusive(LocalDate day) {
        return day.plusDays(1).atStartOfDay().minusSeconds(BUSINESS_DAY_SHIFT_SECONDS);
    }

    /**
     * 把营业日折算成统计桶：日 = 当天；周 = 所在 ISO 周（周一~周日）；月 = 自然月；年 = 自然年。
     *
     * @param granularity 聚合粒度
     * @param day         营业日
     * @return 该营业日所属的桶（含起止与展示标签；标签是**数据**，前端直接展示，不再自行推算周数）
     */
    public static TimeBucket bucket(ReportGranularity granularity, LocalDate day) {
        return switch (granularity) {
            case DAY -> new TimeBucket(day, day, day.toString());
            case WEEK -> weekBucket(day);
            case MONTH -> new TimeBucket(day.withDayOfMonth(1), day.withDayOfMonth(day.lengthOfMonth()),
                    String.format("%04d-%02d", day.getYear(), day.getMonthValue()));
            case YEAR -> new TimeBucket(day.withDayOfYear(1), day.withDayOfYear(day.lengthOfYear()),
                    String.format("%04d年", day.getYear()));
        };
    }

    /**
     * ISO 周桶：周起点固定为**周一**，终点为周日。
     *
     * <p>标签用 **ISO 周年**（{@link IsoFields#WEEK_BASED_YEAR}）而不是自然年：跨年周
     * （如 2025-12-29 ~ 2026-01-04）整体属于 2026 年第 1 周，标签写 2026 才不会让运营
     * 以为「同一周被拆成两年」。
     */
    private static TimeBucket weekBucket(LocalDate day) {
        LocalDate start = day.with(DayOfWeek.MONDAY);
        LocalDate end = start.plusDays(6);
        int weekYear = day.get(IsoFields.WEEK_BASED_YEAR);
        int week = day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return new TimeBucket(start, end, String.format("%04d年第%d周", weekYear, week));
    }

    /**
     * 统计桶：营业日区间 {@code [start, end]}（闭区间）与展示标签。
     *
     * <p>不可变值对象：既做聚合分组键（{@link #start} 唯一决定桶），也做响应字段
     * （前端按 {@link #label} 展示「2026-09-19 / 2026年第38周 / 2026-09 / 2026年」这类标签）。
     */
    public record TimeBucket(LocalDate start, LocalDate end, String label) implements Comparable<TimeBucket> {

        /** 桶按起点排序（同一起点即同一桶）。 */
        @Override
        public int compareTo(TimeBucket other) {
            return start.compareTo(other.start);
        }
    }
}

package io.openware.infrastructure.time;

import io.openware.common.exception.ApiException;
import java.util.Locale;

/**
 * 报表聚合粒度（日 / 周 / 月 / 年）的**唯一解析实现**（与 {@link TimeRangeParams} 同属「报表读口径」）。
 *
 * <p>与 {@code from}/{@code to} 的关系：{@code from}/{@code to} 决定取数区间，粒度只决定**区间内如何分桶**，
 * 两者互不替代。桶边界（周起点、月/年首尾）由 {@link ReportTimeBuckets} 统一计算，
 * 报表实现里**不得**再写第二套周/月算法。
 *
 * <p>缺省语义：不传（{@code null}/空白）即 {@link #DAY} —— 与历史报表「按天一行」的行为一致，
 * 老调用方不传粒度时看到的结果不变（仅营业日边界按 UTC+8 归正，见 {@link ReportTimeBuckets}）。
 *
 * <p>非法值不静默降级：无法识别的粒度直接 400 {@link #CODE_GRANULARITY_INVALID}，
 * 否则用户以为按周看了、实际看到的是按天，属于静默错数据。
 */
public enum ReportGranularity {

    /** 日：每个营业日一个桶。 */
    DAY,
    /** 周：ISO 周（周一起、周日止），标签按 ISO 周所属年（如 2025-12-29 属于 2026 年第 1 周）。 */
    WEEK,
    /** 月：自然月（1 日 ~ 月末）。 */
    MONTH,
    /** 年：自然年（1 月 1 日 ~ 12 月 31 日）。 */
    YEAR;

    /** 统一错误码：粒度取值非法。**全仓唯一**，与 {@link TimeRangeParams#CODE_TIME_RANGE_INVALID} 同族。 */
    public static final String CODE_GRANULARITY_INVALID = "GRANULARITY_INVALID";

    /** 统一中文提示。 */
    public static final String MESSAGE_GRANULARITY_INVALID = "统计粒度不合法，仅支持 DAY/WEEK/MONTH/YEAR";

    /**
     * 解析粒度参数：{@code null}/空白 → {@link #DAY}；大小写不敏感；非法值 → 400。
     *
     * @param raw 请求参数原值
     * @return 非空粒度
     * @throws ApiException 400 {@link #CODE_GRANULARITY_INVALID}
     */
    public static ReportGranularity parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DAY;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(400, CODE_GRANULARITY_INVALID,
                    MESSAGE_GRANULARITY_INVALID + "（收到: " + raw.trim() + "）");
        }
    }
}

package com.gvchat.infrastructure.time;

import com.gvchat.common.exception.ApiException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 后台列表「时间区间查询」参数的**唯一解析实现**（全仓统一口径）。
 *
 * <p>所有带时间列的后台列表读端点都用 {@code from} / {@code to} 两个查询参数，语义如下：
 * <ul>
 *   <li><b>闭区间</b>：{@code from <= 业务时间列 <= to}，两端都取得到；</li>
 *   <li><b>日期收口</b>：{@code yyyy-MM-dd} 形态的 {@code from} 取当天 {@code 00:00:00.000}，
 *       {@code to} 取当天 {@code 23:59:59.999}（列类型 {@code DATETIME(3)} 的精度上限）。
 *       前端因此只需要传日期，不必自己拼时分秒；</li>
 *   <li><b>时刻形态</b>：{@code yyyy-MM-ddTHH:mm:ss}（也兼容 {@code yyyy-MM-dd HH:mm:ss}、
 *       {@code yyyy-MM-ddTHH:mm} 与带毫秒的 {@code .SSS}）按字面值使用，**不再收口**；</li>
 *   <li><b>空值不筛</b>：参数为 {@code null}/空白 → 返回 {@code null}，调用方按「用户没筛」处理
 *       （不拼 SQL 条件），因此后端不会收到空串也不会把它当非法值；</li>
 *   <li><b>非法即拒绝</b>：格式非法或 {@code from > to} → 400 {@link #CODE_TIME_RANGE_INVALID}，
 *       不做静默忽略（忽略会让用户以为筛过了）。</li>
 * </ul>
 *
 * <p><b>为什么用 23:59:59.999 而不是 {@link LocalTime#MAX}</b>：{@code LocalTime.MAX} 是
 * {@code 23:59:59.999999999}，MySQL 在 {@code DATETIME(3)} 上会把它四舍五入到**次日 00:00:00**，
 * 于是「查到今天为止」会多带出明天的数据。用列精度上限既满足闭区间语义又不会溢出到次日。
 *
 * <p><b>SQL 形态</b>：调用方一律拼 {@code column >= from AND column <= to}（或 {@code < toExclusive}），
 * 绝不用函数包裹时间列，保证索引可用。
 */
public final class TimeRangeParams {

    /** 统一错误码：时间区间不合法（格式非法，或 {@code from > to}）。**全仓唯一**，不再各域自造。 */
    public static final String CODE_TIME_RANGE_INVALID = "TIME_RANGE_INVALID";

    /** 统一中文提示：起始时间晚于结束时间。 */
    public static final String MESSAGE_TIME_RANGE_INVALID = "时间区间不合法，起始时间不能晚于结束时间";

    /** {@code from} 的日期收口：当天零点。 */
    public static final LocalTime START_OF_DAY = LocalTime.MIDNIGHT;

    /** {@code to} 的日期收口：当天最后一毫秒（{@code DATETIME(3)} 精度上限）。 */
    public static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59, 999_000_000);

    private static final int DATE_ONLY_LENGTH = 10;

    /** 一毫秒的纳秒数：业务时间列统一 {@code DATETIME(3)}，闭区间端点换算成排他上界时加 1 毫秒。 */
    private static final long MILLIS_NANOS = 1_000_000L;

    private static final DateTimeFormatter ISO_MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final DateTimeFormatter SPACE_MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SPACE_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter SPACE_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 时刻形态的接受顺序：秒精度优先，再毫秒，最后分钟（互不重叠，命中即返回）。 */
    private static final DateTimeFormatter[] FORMATTERS = {
        ISO_SECONDS, ISO_MILLIS, ISO_MINUTES, SPACE_SECONDS, SPACE_MILLIS, SPACE_MINUTES,
    };

    private TimeRangeParams() {
    }

    /**
     * 解析 {@code from}/{@code to} 为闭区间，并校验先后顺序。
     *
     * @param from 起始时间参数，可为 {@code null}/空白（表示不筛）
     * @param to   结束时间参数，可为 {@code null}/空白（表示不筛）
     * @return 两端都可能为 {@code null} 的 {@link TimeRange}；调用方按 {@link TimeRange#hasFrom()} /
     *     {@link TimeRange#hasTo()} 决定是否拼条件
     * @throws ApiException 400 {@link #CODE_TIME_RANGE_INVALID}：格式非法或 {@code from > to}
     */
    public static TimeRange parse(String from, String to) {
        LocalDateTime fromAt = parseFrom(from);
        LocalDateTime toAt = parseTo(to);
        if (fromAt != null && toAt != null && fromAt.isAfter(toAt)) {
            throw new ApiException(400, CODE_TIME_RANGE_INVALID, MESSAGE_TIME_RANGE_INVALID);
        }
        return new TimeRange(fromAt, toAt);
    }

    /** 只解析起始端（{@code yyyy-MM-dd} → 当天 {@code 00:00:00.000}）。 */
    public static LocalDateTime parseFrom(String raw) {
        String value = normalize(raw);
        if (value == null) {
            return null;
        }
        LocalDate date = dateOnly(value);
        return date != null ? date.atTime(START_OF_DAY) : parseDateTime(value);
    }

    /** 只解析结束端（{@code yyyy-MM-dd} → 当天 {@code 23:59:59.999}）。 */
    public static LocalDateTime parseTo(String raw) {
        String value = normalize(raw);
        if (value == null) {
            return null;
        }
        LocalDate date = dateOnly(value);
        return date != null ? date.atTime(END_OF_DAY) : parseDateTime(value);
    }

    /**
     * 结束端的**排他上界**，给内部按 {@code [from, to)} 过滤的消费方（如审计仓储
     * {@code occurred_at >= from AND occurred_at < to}）使用。
     *
     * <p>语义仍是「闭区间的结束日整天都要命中」，只是换算成左闭右开形态：
     * <ul>
     *   <li>日期形态 {@code yyyy-MM-dd} → **次日 {@code 00:00:00}**（等价于当天 23:59:59.999 的闭区间）；</li>
     *   <li>时刻形态 → 该时刻 {@code + 1ms}（列是 {@code DATETIME(3)}，加 1 毫秒才包含该毫秒本身）。</li>
     * </ul>
     *
     * <p>不要把它和 {@link #parseTo} 混用：两者对同一入参给出不同值，用错会让「查到某天为止」少一天。
     */
    public static LocalDateTime parseToExclusive(String raw) {
        String value = normalize(raw);
        if (value == null) {
            return null;
        }
        LocalDate date = dateOnly(value);
        if (date != null) {
            return date.plusDays(1).atStartOfDay();
        }
        return parseDateTime(value).plusNanos(MILLIS_NANOS);
    }

    /** 便于调用方做「统一错误码」判定的 400 异常构造器。 */
    public static ApiException invalid(String message) {
        return new ApiException(400, CODE_TIME_RANGE_INVALID, message);
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }

    /** 严格的 {@code yyyy-MM-dd} 判定：长度与字符位都要对，避免把 {@code 2026-9-1} 当合法日期。 */
    private static LocalDate dateOnly(String value) {
        if (value.length() != DATE_ONLY_LENGTH) {
            return null;
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static LocalDateTime parseDateTime(String value) {
        for (DateTimeFormatter formatter : FORMATTERS) {
            try {
                return LocalDateTime.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // 继续尝试下一种形态
            }
        }
        throw invalid("时间参数格式非法: " + value
                + "，应为 yyyy-MM-dd 或 yyyy-MM-ddTHH:mm:ss");
    }

    /**
     * 已解析的时间区间：两端都可能为 {@code null}（表示该端不筛）。
     *
     * <p>不可变值对象，跨 controller/service 传递，让「解析 + 校验」只发生一次。
     */
    public record TimeRange(LocalDateTime fromInclusive, LocalDateTime toInclusive) {

        /** 空区间：两端都不筛。 */
        public static TimeRange none() {
            return new TimeRange(null, null);
        }

        public boolean hasFrom() {
            return fromInclusive != null;
        }

        public boolean hasTo() {
            return toInclusive != null;
        }

        /** 两端都不筛 → 无需额外拼条件。 */
        public boolean isEmpty() {
            return fromInclusive == null && toInclusive == null;
        }
    }
}

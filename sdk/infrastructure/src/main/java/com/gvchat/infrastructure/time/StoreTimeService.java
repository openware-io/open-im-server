package com.gvchat.infrastructure.time;

import com.gvchat.common.exception.ApiException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * 门店时间换算的**唯一实现**（`docs/renovation/MULTI_TIMEZONE_DESIGN.md` §2.1 / §3.2 / §4.2 S19）。
 *
 * <p>职责边界：
 * <ul>
 *   <li><b>时区校验</b>：只接受 IANA 时区 id（`Asia/Shanghai`、`Asia/Bangkok`、`America/New_York`），
 *       拒绝 `+08:00` / `Z` 这类偏移字面量 —— 偏移没有 DST 规则，切换日会错 1 小时（文档 §2.1、TC-U10）；</li>
 *   <li><b>营业日</b>：按「绝对时刻 → 门店时区 → 与营业日切点比较」三步求 {@link LocalDate}（文档 §3.2）；</li>
 *   <li><b>营业日时间窗</b>：由一个营业日反推左闭右开的 {@code [start, end)} 绝对时刻区间（文档 §2.3）。</li>
 * </ul>
 *
 * <p>**不做什么**：本类不读数据库、不持有门店配置、不重算历史记录。门店配置的读取与降级策略由调用方负责：
 * 门店时区 → 租户 `default_timezone` → 平台默认 {@link #DEFAULT_TIMEZONE}；**写路径 fail-closed**
 * （见 {@link #requireStoreZoneId(String)}，文档 §2.1 决策点 2）；**读路径降级**
 * （见 {@link #resolveZoneId(String, String)}，降级时打 warn，文档 §7 G2）。
 *
 * <p>**存储口径**：业务时间列一律 `DATETIME(3)` 存 UTC 墙钟字面量（文档 §2.2 A1），
 * 因此 {@link #toUtc(OffsetDateTime)} 与 {@link #toStoreOffset(LocalDateTime, ZoneId)} 是读写边界的两个换算点；
 * **禁止**把列类型改成 `TIMESTAMP`（MySQL 会按会话时区做隐式转换，各服务 `serverTimezone` 不一致时会读出不同值）。
 *
 * <p>**DST 语义**（文档 §6.4 TC-U5/U6、TC-D3）：
 * <ul>
 *   <li>夏季回拨日同一墙上时间出现两次：{@link #businessDate(Instant, ZoneId, LocalTime)} 按绝对时刻计算，
 *       两个时刻给出各自的结果，不会因为墙上时间重复而合并成同一时刻；</li>
 *   <li>春季前拨日不存在的墙上时间（例如 `America/New_York` 2026-03-08 02:30）只可能来自配置的切点，
 *       {@link #businessDayWindow(LocalDate, ZoneId, LocalTime)} 采用 Java `ZonedDateTime.of` 的默认解析
 *       —— **前移**到偏移生效后的时刻（02:30 → 03:30 EDT），不抛异常。</li>
 * </ul>
 */
@Slf4j
public final class StoreTimeService {

    /** 平台默认时区：租户 `default_timezone` 与门店 `timezone` 都为空时使用（文档 §2.1）。 */
    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    /** 平台默认营业日切点：KTV 通宵场次归前一营业日（文档 §3.1）。 */
    public static final LocalTime DEFAULT_BUSINESS_DAY_CUTOFF = LocalTime.of(4, 0);

    /** 营业日切点允许的最大值：超过 12:00 会把早班归到前一营业日（文档 §3.1）。**含**该值。 */
    public static final LocalTime MAX_BUSINESS_DAY_CUTOFF = LocalTime.of(12, 0);

    /** 错误码：门店时区缺失，写路径 fail-closed（文档 §7 G2）。 */
    public static final String CODE_STORE_TIMEZONE_MISSING = "STORE_TIMEZONE_MISSING";

    /** 错误码：非法 IANA 时区 id（含 `+08:00` 这类偏移字面量）。 */
    public static final String CODE_TIMEZONE_INVALID = "TIMEZONE_INVALID";

    /** 错误码：营业日切点格式非法（要求 `HH:mm` 或 `HH:mm:ss`）。 */
    public static final String CODE_BUSINESS_DAY_CUTOFF_INVALID = "BUSINESS_DAY_CUTOFF_INVALID";

    /** 错误码：营业日切点超出 `00:00`–`12:00`。 */
    public static final String CODE_BUSINESS_DAY_CUTOFF_OUT_OF_RANGE = "BUSINESS_DAY_CUTOFF_OUT_OF_RANGE";

    /** 切点对外文本形态：分钟精度（列类型 TIME 读出来是 `HH:mm:ss`，出参统一收敛）。 */
    private static final DateTimeFormatter CUTOFF_TEXT = DateTimeFormatter.ofPattern("HH:mm");

    private StoreTimeService() {
    }

    // ---------------------------------------------------------------- 时区

    /**
     * 严格校验并解析 IANA 时区 id（写路径）。
     *
     * <p>拒绝三类值：空、无法被 {@link ZoneId#of(String)} 解析、解析结果是偏移字面量
     * （`+08:00`、`Z`、`UTC` 之外的 `ZoneOffset`）。命中任一 → 400 {@link #CODE_TIMEZONE_INVALID}。
     */
    public static ZoneId requireZoneId(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw new ApiException(400, CODE_TIMEZONE_INVALID,
                    "时区不能为空，必须使用 IANA 时区 id，例如 Asia/Shanghai");
        }
        String candidate = timezone.trim();
        ZoneId zone;
        try {
            zone = ZoneId.of(candidate);
        } catch (DateTimeException failure) {
            throw new ApiException(400, CODE_TIMEZONE_INVALID, "非法 IANA 时区 id: " + candidate);
        }
        if (zone instanceof ZoneOffset) {
            throw new ApiException(400, CODE_TIMEZONE_INVALID,
                    "时区必须是 IANA id，不接受偏移字面量: " + candidate + "（偏移没有 DST 规则，切换日会错 1 小时）");
        }
        return zone;
    }

    /**
     * 门店时区缺失即拒绝（写路径 fail-closed，文档 §2.1 决策点 2）。
     *
     * <p>时间写错比拒绝服务更难回滚：宁可让调用方看到 400，也不要按不确定口径把墙钟写进库。
     */
    public static ZoneId requireStoreZoneId(String storeTimezone) {
        if (storeTimezone == null || storeTimezone.isBlank()) {
            throw new ApiException(400, CODE_STORE_TIMEZONE_MISSING,
                    "门店未配置时区，拒绝按不确定口径写入时间");
        }
        return requireZoneId(storeTimezone);
    }

    /**
     * 读路径时区降级：门店 `timezone` → 租户 `default_timezone` → 平台默认 {@link #DEFAULT_TIMEZONE}。
     *
     * <p>任一层为空或非法（含偏移字面量）都继续往下一层回落，**绝不抛异常**：读路径不能让后台白屏，
     * 也不能因为一个脏值阻断整张列表。整条链都不可用时打一条 warn，便于按门店补录（文档 §7 G2）。
     */
    public static ZoneId resolveZoneId(String storeTimezone, String tenantTimezone) {
        ZoneId fromStore = lenientZone(storeTimezone);
        if (fromStore != null) {
            return fromStore;
        }
        ZoneId fromTenant = lenientZone(tenantTimezone);
        if (fromTenant != null) {
            return fromTenant;
        }
        log.warn("store timezone unusable (store={}, tenant={}), fallback to platform default {}",
                storeTimezone, tenantTimezone, DEFAULT_TIMEZONE);
        return ZoneId.of(DEFAULT_TIMEZONE);
    }

    /** 宽松解析：空值/非法值/偏移字面量一律返回 {@code null}，交由调用方决定回落顺序。 */
    private static ZoneId lenientZone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            ZoneId zone = ZoneId.of(raw.trim());
            return zone instanceof ZoneOffset ? null : zone;
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    // ------------------------------------------------------- 营业日切点

    /**
     * 严格解析营业日切点（写路径）：接受 `HH:mm` 与 `HH:mm:ss`，秒级精度按分钟截断。
     *
     * <p>范围校验为 `00:00`–`12:00`（含）；超出 → 400 {@link #CODE_BUSINESS_DAY_CUTOFF_OUT_OF_RANGE}。
     */
    public static LocalTime requireBusinessDayCutoff(String cutoff) {
        if (cutoff == null || cutoff.isBlank()) {
            throw new ApiException(400, CODE_BUSINESS_DAY_CUTOFF_INVALID,
                    "营业日切点不能为空，格式 HH:mm，取值 00:00-12:00");
        }
        LocalTime parsed = parseCutoff(cutoff.trim());
        if (parsed == null) {
            throw new ApiException(400, CODE_BUSINESS_DAY_CUTOFF_INVALID,
                    "营业日切点格式非法: " + cutoff + "，应为 HH:mm，取值 00:00-12:00");
        }
        if (parsed.isAfter(MAX_BUSINESS_DAY_CUTOFF)) {
            throw new ApiException(400, CODE_BUSINESS_DAY_CUTOFF_OUT_OF_RANGE,
                    "营业日切点必须在 00:00-12:00 之间: " + cutoff);
        }
        return parsed;
    }

    /**
     * 读路径切点降级：空值、非法格式、超出 `00:00`–`12:00` 一律回落 {@link #DEFAULT_BUSINESS_DAY_CUTOFF}。
     *
     * <p>切点留空会让「营业日」退化为自然日（文档 §3.1），因此读路径宁可回落默认值也不返回 {@code null}。
     */
    public static LocalTime resolveBusinessDayCutoff(String cutoff) {
        if (cutoff == null || cutoff.isBlank()) {
            return DEFAULT_BUSINESS_DAY_CUTOFF;
        }
        LocalTime parsed = parseCutoff(cutoff.trim());
        if (parsed == null || parsed.isAfter(MAX_BUSINESS_DAY_CUTOFF)) {
            return DEFAULT_BUSINESS_DAY_CUTOFF;
        }
        return parsed;
    }

    /** 切点的契约文本：`HH:mm`（{@code null} 视为平台默认）。 */
    public static String formatBusinessDayCutoff(LocalTime cutoff) {
        return CUTOFF_TEXT.format(cutoff == null ? DEFAULT_BUSINESS_DAY_CUTOFF : cutoff);
    }

    /** 解析 `HH:mm` / `HH:mm:ss`（亦兼容 GROUP BY 出来的秒级字面量）；无法解析返回 {@code null}。 */
    private static LocalTime parseCutoff(String raw) {
        try {
            return LocalTime.parse(raw).truncatedTo(ChronoUnit.MINUTES);
        } catch (DateTimeParseException failure) {
            return null;
        }
    }

    // ------------------------------------------------------------ 营业日

    /**
     * 营业日公式（文档 §3.2，本类是唯一实现）：
     *
     * <pre>
     * local        = instant.atZone(storeZone)
     * businessDate = local.toLocalTime() &lt; cutoff ? local.toLocalDate().minusDays(1) : local.toLocalDate()
     * </pre>
     *
     * <p>右开：本地墙上时间**恰好等于**切点时归当天（TC-U4）。{@code cutoff} 为 {@code null} 时按
     * {@link #DEFAULT_BUSINESS_DAY_CUTOFF} 处理。
     */
    public static LocalDate businessDate(Instant instant, ZoneId storeZone, LocalTime cutoff) {
        Instant moment = Objects.requireNonNull(instant, "instant");
        ZoneId zone = Objects.requireNonNull(storeZone, "storeZone");
        LocalTime boundary = cutoff == null ? DEFAULT_BUSINESS_DAY_CUTOFF : cutoff;
        LocalDateTime local = moment.atZone(zone).toLocalDateTime();
        LocalDate date = local.toLocalDate();
        return local.toLocalTime().isBefore(boundary) ? date.minusDays(1) : date;
    }

    /** 同上，入参为 `DATETIME(3)` 列里存的 UTC 墙钟字面量。 */
    public static LocalDate businessDate(LocalDateTime utcWallClock, ZoneId storeZone, LocalTime cutoff) {
        LocalDateTime moment = Objects.requireNonNull(utcWallClock, "utcWallClock");
        return businessDate(moment.toInstant(ZoneOffset.UTC), storeZone, cutoff);
    }

    /**
     * 由营业日反推该门店的左闭右开时间窗 {@code [startInclusive, endExclusive)}（文档 §2.3、§3.2）。
     *
     * <p>窗外边界用门店时区的真实偏移计算，因此 DST 切换日的营业日窗口是 23 小时或 25 小时，
     * 与「营业日」的业务含义一致（不因自然日长度而错位，文档 §7 C3）。
     */
    public static BusinessDayWindow businessDayWindow(LocalDate businessDate, ZoneId storeZone, LocalTime cutoff) {
        LocalDate date = Objects.requireNonNull(businessDate, "businessDate");
        ZoneId zone = Objects.requireNonNull(storeZone, "storeZone");
        LocalTime boundary = cutoff == null ? DEFAULT_BUSINESS_DAY_CUTOFF : cutoff;
        Instant start = ZonedDateTime.of(date, boundary, zone).toInstant();
        Instant end = ZonedDateTime.of(date.plusDays(1), boundary, zone).toInstant();
        return new BusinessDayWindow(date, start, end);
    }

    /** 营业日时间窗：左闭右开的绝对时刻区间。 */
    public record BusinessDayWindow(LocalDate businessDate, Instant startInclusive, Instant endExclusive) {

        /** 起点在门店时区下的墙上时间（展示与日志用）。 */
        public ZonedDateTime startAt(ZoneId storeZone) {
            return startInclusive.atZone(storeZone);
        }

        /** 终点在门店时区下的墙上时间（展示与日志用）。 */
        public ZonedDateTime endAt(ZoneId storeZone) {
            return endExclusive.atZone(storeZone);
        }
    }

    // -------------------------------------------------- 读写边界换算

    /** 读/写边界①：带偏移入参 → UTC 墙钟字面量（落库值，文档 §2.2 A1）。 */
    public static LocalDateTime toUtc(OffsetDateTime offsetDateTime) {
        OffsetDateTime moment = Objects.requireNonNull(offsetDateTime, "offsetDateTime");
        return LocalDateTime.ofInstant(moment.toInstant(), ZoneOffset.UTC);
    }

    /** 读/写边界②：UTC 墙钟字面量 → 门店时区下带偏移的出参（自动覆盖 DST，文档 §2.3）。 */
    public static OffsetDateTime toStoreOffset(LocalDateTime utcWallClock, ZoneId storeZone) {
        LocalDateTime moment = Objects.requireNonNull(utcWallClock, "utcWallClock");
        ZoneId zone = Objects.requireNonNull(storeZone, "storeZone");
        return moment.toInstant(ZoneOffset.UTC).atZone(zone).toOffsetDateTime();
    }
}

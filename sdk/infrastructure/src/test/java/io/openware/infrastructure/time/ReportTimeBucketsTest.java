package io.openware.infrastructure.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.time.ReportTimeBuckets.TimeBucket;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 报表统计桶口径守护：营业日（门店时区 + 营业日切点）、取数窗口对齐、周一起的 ISO 周、月/年首尾。
 *
 * <p>这些断言是「报表分桶唯一实现」的可执行版本：任何报表若自行推算周/月边界，
 * 只要与这里不一致就会被挡住（口径写在 {@link ReportTimeBuckets} 的 javadoc 里）。
 *
 * <p>其中「与 {@link StoreTimeService} 逐点等价」这组断言尤其重要：报表把营业日下推成了
 * 一个固定偏移，如果平台默认时区/切点变了而偏移没跟着变，这里会立刻红。
 */
class ReportTimeBucketsTest {

    /** 平台默认门店口径（Asia/Shanghai + 04:00 切点）下的平移量：8h − 4h = 4h。 */
    private static final int EXPECTED_SHIFT_SECONDS = 4 * 3600;

    @Nested
    @DisplayName("营业日：门店本地日 + 营业日切点（KTV 通宵归前一营业日）")
    class BusinessDay {

        @Test
        @DisplayName("平移量 = 时区偏移 − 营业日切点（默认口径为 4 小时）")
        void shiftSecondsFollowsPlatformDefaults() {
            int zoneSeconds = ZoneId.of(StoreTimeService.DEFAULT_TIMEZONE).getRules()
                    .getOffset(Instant.now()).getTotalSeconds();
            assertThat(ReportTimeBuckets.BUSINESS_DAY_SHIFT_SECONDS)
                    .isEqualTo(zoneSeconds - StoreTimeService.DEFAULT_BUSINESS_DAY_CUTOFF.toSecondOfDay());
            assertThat(ReportTimeBuckets.BUSINESS_DAY_SHIFT_SECONDS).isEqualTo(EXPECTED_SHIFT_SECONDS);
        }

        @Test
        @DisplayName("04:00 切点：凌晨 02:00 的场次仍属前一营业日，04:00 起归新营业日")
        void cutoffDecidesBusinessDay() {
            // 存储 2026-09-19T18:00 = 门店 2026-09-20 02:00（通宵）→ 营业日 2026-09-19
            assertThat(ReportTimeBuckets.businessDay(LocalDateTime.of(2026, 9, 19, 18, 0)))
                    .isEqualTo(LocalDate.of(2026, 9, 19));
            // 存储 2026-09-19T19:59:59 = 门店 2026-09-20 03:59:59 → 仍属 2026-09-19
            assertThat(ReportTimeBuckets.businessDay(LocalDateTime.of(2026, 9, 19, 19, 59, 59)))
                    .isEqualTo(LocalDate.of(2026, 9, 19));
            // 存储 2026-09-19T20:00 = 门店 2026-09-20 04:00（恰好等于切点，右开归当天）→ 2026-09-20
            assertThat(ReportTimeBuckets.businessDay(LocalDateTime.of(2026, 9, 19, 20, 0)))
                    .isEqualTo(LocalDate.of(2026, 9, 20));
        }

        @ParameterizedTest(name = "{0} 与 StoreTimeService 口径一致")
        @ValueSource(strings = {
            "2026-09-18T19:59:59", "2026-09-18T20:00:00", "2026-09-19T01:00:00", "2026-09-19T10:00:00",
            "2026-09-19T18:00:00", "2026-09-19T19:59:59", "2026-09-19T20:00:00", "2026-09-20T03:00:00",
            "2026-12-31T20:00:00", "2027-01-01T19:00:00",
        })
        @DisplayName("固定偏移实现与权威 StoreTimeService.businessDate 逐点等价（含跨年）")
        void matchesStoreTimeService(String stored) {
            LocalDateTime storedUtc = LocalDateTime.parse(stored);
            LocalDate expected = StoreTimeService.businessDate(storedUtc,
                    ZoneId.of(StoreTimeService.DEFAULT_TIMEZONE), StoreTimeService.DEFAULT_BUSINESS_DAY_CUTOFF);
            assertThat(ReportTimeBuckets.businessDay(storedUtc)).isEqualTo(expected);
        }

        @Test
        @DisplayName("取数窗口与营业日严格对齐，且与 StoreTimeService.businessDayWindow 同源")
        void windowMatchesBusinessDayWindow() {
            LocalDate day = LocalDate.of(2026, 9, 19);
            LocalDateTime from = ReportTimeBuckets.windowFrom(day);
            LocalDateTime toExclusive = ReportTimeBuckets.windowToExclusive(day);

            assertThat(from).isEqualTo(LocalDateTime.of(2026, 9, 18, 20, 0));
            assertThat(toExclusive).isEqualTo(LocalDateTime.of(2026, 9, 19, 20, 0));

            StoreTimeService.BusinessDayWindow window = StoreTimeService.businessDayWindow(day,
                    ZoneId.of(StoreTimeService.DEFAULT_TIMEZONE), StoreTimeService.DEFAULT_BUSINESS_DAY_CUTOFF);
            assertThat(from).isEqualTo(LocalDateTime.ofInstant(window.startInclusive(), ZoneOffset.UTC));
            assertThat(toExclusive).isEqualTo(LocalDateTime.ofInstant(window.endExclusive(), ZoneOffset.UTC));

            // 半开区间：下界含、上界不含；窗口两端恰好落在两个相邻营业日的分界上
            assertThat(ReportTimeBuckets.businessDay(from)).isEqualTo(day);
            assertThat(ReportTimeBuckets.businessDay(toExclusive.minusNanos(1_000_000))).isEqualTo(day);
            assertThat(ReportTimeBuckets.businessDay(toExclusive)).isEqualTo(day.plusDays(1));
        }

        @Test
        @DisplayName("businessToday 是 UTC 当前时刻的营业日（不依赖 JVM 默认时区）")
        void businessTodayUsesUtcClock() {
            LocalDate expected = StoreTimeService.businessDate(Instant.now(),
                    ZoneId.of(StoreTimeService.DEFAULT_TIMEZONE), StoreTimeService.DEFAULT_BUSINESS_DAY_CUTOFF);
            assertThat(ReportTimeBuckets.businessToday()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("日桶")
    class DayBucket {

        @Test
        @DisplayName("日桶起止都是当天，标签 yyyy-MM-dd")
        void dayBucketIsTheDayItself() {
            TimeBucket bucket = ReportTimeBuckets.bucket(ReportGranularity.DAY, LocalDate.of(2026, 9, 19));
            assertThat(bucket.start()).isEqualTo(LocalDate.of(2026, 9, 19));
            assertThat(bucket.end()).isEqualTo(LocalDate.of(2026, 9, 19));
            assertThat(bucket.label()).isEqualTo("2026-09-19");
        }
    }

    @Nested
    @DisplayName("周桶：周一起、周日止（ISO 周）")
    class WeekBucket {

        @ParameterizedTest(name = "{0} 与周内其它日同桶")
        @ValueSource(strings = {"2026-09-14", "2026-09-15", "2026-09-16", "2026-09-17", "2026-09-18", "2026-09-19", "2026-09-20"})
        @DisplayName("同一周（周一~周日）7 天映射到同一个桶")
        void wholeWeekMapsToOneBucket(String day) {
            TimeBucket bucket = ReportTimeBuckets.bucket(ReportGranularity.WEEK, LocalDate.parse(day));
            assertThat(bucket.start()).isEqualTo(LocalDate.of(2026, 9, 14));
            assertThat(bucket.end()).isEqualTo(LocalDate.of(2026, 9, 20));
            assertThat(bucket.label()).isEqualTo("2026年第38周");
        }

        @Test
        @DisplayName("跨月的周：2026-08-31(周一) ~ 2026-09-06 是同一个桶")
        void weekCrossingMonthBoundary() {
            TimeBucket bucket = ReportTimeBuckets.bucket(ReportGranularity.WEEK, LocalDate.of(2026, 9, 1));
            assertThat(bucket.start()).isEqualTo(LocalDate.of(2026, 8, 31));
            assertThat(bucket.end()).isEqualTo(LocalDate.of(2026, 9, 6));
            assertThat(bucket.label()).isEqualTo("2026年第36周");
        }

        @Test
        @DisplayName("跨年的周：2025-12-29 ~ 2026-01-04 属于 2026 年第 1 周（按 ISO 周年，不按自然年）")
        void weekCrossingYearBoundary() {
            TimeBucket bucket = ReportTimeBuckets.bucket(ReportGranularity.WEEK, LocalDate.of(2025, 12, 29));
            assertThat(bucket.start()).isEqualTo(LocalDate.of(2025, 12, 29));
            assertThat(bucket.end()).isEqualTo(LocalDate.of(2026, 1, 4));
            assertThat(bucket.label()).isEqualTo("2026年第1周");
            // 同一周的 2026-01-01 / 01-04 必须落进同一个桶（年末年初不劈开）
            assertThat(ReportTimeBuckets.bucket(ReportGranularity.WEEK, LocalDate.of(2026, 1, 1)))
                    .isEqualTo(bucket);
            assertThat(ReportTimeBuckets.bucket(ReportGranularity.WEEK, LocalDate.of(2026, 1, 4)))
                    .isEqualTo(bucket);
        }

        @Test
        @DisplayName("年末跨到次年的周：2026-12-28 ~ 2027-01-03 是 2026 年第 53 周")
        void weekCrossingEndOfYear() {
            TimeBucket bucket = ReportTimeBuckets.bucket(ReportGranularity.WEEK, LocalDate.of(2026, 12, 31));
            assertThat(bucket.start()).isEqualTo(LocalDate.of(2026, 12, 28));
            assertThat(bucket.end()).isEqualTo(LocalDate.of(2027, 1, 3));
            assertThat(bucket.label()).isEqualTo("2026年第53周");
        }

        @Test
        @DisplayName("周起点恒为周一（不含周日起算的口径）")
        void weekAlwaysStartsOnMonday() {
            TimeBucket bucket = ReportTimeBuckets.bucket(ReportGranularity.WEEK, LocalDate.of(2026, 3, 1));
            assertThat(bucket.start().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
            assertThat(bucket.end().getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
            assertThat(bucket.label()).isEqualTo("2026年第9周");
        }
    }

    @Nested
    @DisplayName("月桶 / 年桶")
    class MonthAndYearBucket {

        @ParameterizedTest(name = "{0} → 2026-09")
        @ValueSource(strings = {"2026-09-01", "2026-09-15", "2026-09-30"})
        @DisplayName("自然月：月初~月末，标签 yyyy-MM")
        void monthBucket(String day) {
            TimeBucket bucket = ReportTimeBuckets.bucket(ReportGranularity.MONTH, LocalDate.parse(day));
            assertThat(bucket.start()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(bucket.end()).isEqualTo(LocalDate.of(2026, 9, 30));
            assertThat(bucket.label()).isEqualTo("2026-09");
        }

        @Test
        @DisplayName("月末按真实天数（闰年 2 月 = 29 天）")
        void monthEndFollowsCalendar() {
            TimeBucket february = ReportTimeBuckets.bucket(ReportGranularity.MONTH, LocalDate.of(2028, 2, 5));
            assertThat(february.end()).isEqualTo(LocalDate.of(2028, 2, 29));
            assertThat(ReportTimeBuckets.bucket(ReportGranularity.MONTH, LocalDate.of(2026, 2, 5)).end())
                    .isEqualTo(LocalDate.of(2026, 2, 28));
        }

        @Test
        @DisplayName("自然年：1 月 1 日 ~ 12 月 31 日，标签 yyyy年")
        void yearBucket() {
            TimeBucket bucket = ReportTimeBuckets.bucket(ReportGranularity.YEAR, LocalDate.of(2026, 6, 1));
            assertThat(bucket.start()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(bucket.end()).isEqualTo(LocalDate.of(2026, 12, 31));
            assertThat(bucket.label()).isEqualTo("2026年");
        }
    }

    @Nested
    @DisplayName("粒度解析")
    class GranularityParsing {

        @Test
        @DisplayName("不传 / 空白 → DAY（老调用方行为不变）")
        void defaultIsDay() {
            assertThat(ReportGranularity.parse(null)).isEqualTo(ReportGranularity.DAY);
            assertThat(ReportGranularity.parse("   ")).isEqualTo(ReportGranularity.DAY);
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({"day,DAY", "week,WEEK", " WEEK ,WEEK", "Month,MONTH", "year,YEAR"})
        @DisplayName("大小写与空白不敏感")
        void caseInsensitive(String raw, String expected) {
            assertThat(ReportGranularity.parse(raw)).isEqualTo(ReportGranularity.valueOf(expected));
        }

        @Test
        @DisplayName("非法粒度 → 400 GRANULARITY_INVALID（不静默降级成 DAY）")
        void invalidGranularityRejected() {
            assertThatThrownBy(() -> ReportGranularity.parse("QUARTER"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(exception -> {
                        ApiException api = (ApiException) exception;
                        assertThat(api.getStatus()).isEqualTo(400);
                        assertThat(api.getCode()).isEqualTo(ReportGranularity.CODE_GRANULARITY_INVALID);
                    });
        }
    }

    @Nested
    @DisplayName("营业日切点的边界（与 StoreTimeService 默认口径一致）")
    class PlatformDefaults {

        @Test
        @DisplayName("平台默认时区是 Asia/Shanghai、切点是 04:00（本类据它算平移量）")
        void defaults() {
            assertThat(ReportTimeBuckets.REPORT_TIMEZONE).isEqualTo("Asia/Shanghai");
            assertThat(ReportTimeBuckets.REPORT_BUSINESS_DAY_CUTOFF).isEqualTo(LocalTime.of(4, 0));
        }
    }
}

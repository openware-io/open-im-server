package com.gvchat.infrastructure.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.time.StoreTimeService.BusinessDayWindow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * 门店时间换算的唯一实现：营业日公式、切点边界、跨零点、DST 春秋切换、时区与切点校验。
 *
 * <p>用例编号对齐 `docs/renovation/MULTI_TIMEZONE_DESIGN.md` §6.1 / §6.4：
 * TC-U1（读入参转 UTC）、TC-U2/U3（跨零点归前一营业日）、TC-U4（切点右开）、
 * TC-U5/U6 与 TC-D3（DST 不抛错且结果可解释）、TC-U10（拒绝偏移字面量）。
 */
class StoreTimeServiceTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime CUTOFF_0400 = LocalTime.of(4, 0);

    /**
     * 产品口径（2026-09-19）：**暂不做逐门店多时区**，全平台统一东八区。
     *
     * <p>守住平台默认常量本身：发号（{@code DailySerialNumberGenerator}）与报表时间桶
     * （{@code ReportTimeBuckets}）都直接引用它们；一旦被改成其它时区/切点，营业日归档口径会整体漂移
     * （见 {@code docs/renovation/MULTI_TIMEZONE_DESIGN.md} 顶部的决策更新）。
     */
    @Test
    void platformDefaultIsEast8WithFourAmBusinessDayCutoff() {
        assertThat(StoreTimeService.DEFAULT_TIMEZONE).isEqualTo("Asia/Shanghai");
        assertThat(StoreTimeService.DEFAULT_BUSINESS_DAY_CUTOFF).isEqualTo(CUTOFF_0400);
        // 东八区全年同一偏移（无夏令时）：取夏/冬两个时刻都应等于 +08:00
        assertThat(ZoneId.of(StoreTimeService.DEFAULT_TIMEZONE).getRules().getOffset(Instant.parse("2026-07-01T00:00:00Z")))
                .isEqualTo(java.time.ZoneOffset.ofHours(8));
        assertThat(ZoneId.of(StoreTimeService.DEFAULT_TIMEZONE).getRules().getOffset(Instant.parse("2026-01-01T00:00:00Z")))
                .isEqualTo(java.time.ZoneOffset.ofHours(8));
    }

    // ------------------------------------------------------------ TC-U1

    @Test
    void toUtcConvertsOffsetInputToUtcWallClock() {
        LocalDateTime utc = StoreTimeService.toUtc(OffsetDateTime.parse("2026-09-20T19:00:00+08:00"));

        assertThat(utc).isEqualTo(LocalDateTime.of(2026, 9, 20, 11, 0));
    }

    @Test
    void toUtcKeepsInstantWhenInputIsAlreadyZulu() {
        assertThat(StoreTimeService.toUtc(OffsetDateTime.parse("2026-09-20T11:00:00Z")))
                .isEqualTo(LocalDateTime.of(2026, 9, 20, 11, 0));
    }

    @Test
    void toStoreOffsetRendersUtcWallClockInStoreZone() {
        OffsetDateTime rendered = StoreTimeService.toStoreOffset(LocalDateTime.of(2026, 9, 20, 11, 0), SHANGHAI);

        assertThat(rendered.toString()).isEqualTo("2026-09-20T19:00+08:00");
    }

    // ------------------------------------------------------- TC-U2 ~ U4

    @Test
    void businessDateFallsBackToPreviousDayBeforeCutoff() {
        // UTC 19:30 = 门店 9/21 03:30 < 切点 04:00 → 归前一营业日 9/20
        LocalDate businessDate = StoreTimeService.businessDate(
                Instant.parse("2026-09-20T19:30:00Z"), SHANGHAI, CUTOFF_0400);

        assertThat(businessDate).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    void businessDateStaysOnSameDayAtOrAfterCutoff() {
        // UTC 20:30 = 门店 9/21 04:30 >= 切点 04:00 → 当天 9/21
        assertThat(StoreTimeService.businessDate(Instant.parse("2026-09-20T20:30:00Z"), SHANGHAI, CUTOFF_0400))
                .isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    void businessDateBoundaryIsRightOpen() {
        // 门店墙上时间恰好 03:59 → 前一营业日；恰好 04:00 → 当天（右开，TC-U4）
        assertThat(StoreTimeService.businessDate(Instant.parse("2026-09-20T19:59:00Z"), SHANGHAI, CUTOFF_0400))
                .isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(StoreTimeService.businessDate(Instant.parse("2026-09-20T20:00:00Z"), SHANGHAI, CUTOFF_0400))
                .isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    void businessDateAcceptsUtcWallClockLiteralFromDatetimeColumn() {
        assertThat(StoreTimeService.businessDate(LocalDateTime.of(2026, 9, 20, 19, 30), SHANGHAI, CUTOFF_0400))
                .isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(StoreTimeService.businessDate(LocalDateTime.of(2026, 9, 20, 20, 30), SHANGHAI, CUTOFF_0400))
                .isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    void businessDateUsesCutoffZeroWhenCutoffIsOmitted() {
        assertThat(StoreTimeService.businessDate(Instant.parse("2026-09-20T23:59:00Z"), SHANGHAI, null))
                .isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    void sameInstantBelongsToDifferentBusinessDatesAcrossStores() {
        // 跨时区门店各自算「日」：UTC 2026-09-20T19:30Z
        //   上海 (+08) 9/21 03:30 < 04:00 → 2026-09-20
        //   曼谷 (+07) 9/21 02:30 >= 02:00 → 2026-09-21
        Instant instant = Instant.parse("2026-09-20T19:30:00Z");

        assertThat(StoreTimeService.businessDate(instant, SHANGHAI, CUTOFF_0400))
                .isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(StoreTimeService.businessDate(instant, BANGKOK, LocalTime.of(2, 0)))
                .isEqualTo(LocalDate.of(2026, 9, 21));
    }

    // ---------------------------------------------------- TC-U5 / U6 DST

    @Test
    void springForwardLocalTimeSkipsTheMissingHour() {
        // 2026-03-08 美东春进：02:00 EST 直接跳到 03:00 EDT，UTC 07:30Z 对应本地 03:30
        Instant instant = Instant.parse("2026-03-08T07:30:00Z");

        assertThat(StoreTimeService.toStoreOffset(LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC), NEW_YORK)
                .toString())
                .isEqualTo("2026-03-08T03:30-04:00");
        // 切点 04:00：03:30 < 04:00 → 归前一营业日；切点 00:00：03:30 >= 00:00 → 当天
        assertThat(StoreTimeService.businessDate(instant, NEW_YORK, CUTOFF_0400))
                .isEqualTo(LocalDate.of(2026, 3, 7));
        assertThat(StoreTimeService.businessDate(instant, NEW_YORK, LocalTime.MIDNIGHT))
                .isEqualTo(LocalDate.of(2026, 3, 8));
    }

    @Test
    void fallBackKeepsTwoDistinctInstantsForTheRepeatedWallClock() {
        // 2026-11-01 美东秋回：本地 01:30 出现两次（EDT 05:30Z / EST 06:30Z），两个绝对时刻都不能丢
        Instant first = Instant.parse("2026-11-01T05:30:00Z");
        Instant second = Instant.parse("2026-11-01T06:30:00Z");

        assertThat(first).isNotEqualTo(second);
        assertThat(StoreTimeService.businessDate(first, NEW_YORK, LocalTime.MIDNIGHT))
                .isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(StoreTimeService.businessDate(second, NEW_YORK, LocalTime.MIDNIGHT))
                .isEqualTo(LocalDate.of(2026, 11, 1));
        // 切点 04:00：两次 01:30 都在切点之前 → 都归 10/31，口径一致且可解释
        assertThat(StoreTimeService.businessDate(first, NEW_YORK, CUTOFF_0400))
                .isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(StoreTimeService.businessDate(second, NEW_YORK, CUTOFF_0400))
                .isEqualTo(LocalDate.of(2026, 10, 31));
    }

    // ------------------------------------------------------ 营业日时间窗

    @Test
    void businessDayWindowIsRightOpenInAbsoluteTime() {
        BusinessDayWindow window = StoreTimeService.businessDayWindow(
                LocalDate.of(2026, 9, 20), SHANGHAI, CUTOFF_0400);

        assertThat(window.startInclusive()).isEqualTo(Instant.parse("2026-09-19T20:00:00Z"));
        assertThat(window.endExclusive()).isEqualTo(Instant.parse("2026-09-20T20:00:00Z"));
        assertThat(Duration.between(window.startInclusive(), window.endExclusive())).isEqualTo(Duration.ofHours(24));
        assertThat(window.businessDate()).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    void businessDayWindowTracksTheBusinessDateInsteadOfTheCalendarDay() {
        // 跨零点营业：营业日 9/20 的窗口在门店本地是 9/20 04:00 → 9/21 04:00
        BusinessDayWindow window = StoreTimeService.businessDayWindow(
                LocalDate.of(2026, 9, 20), SHANGHAI, CUTOFF_0400);

        assertThat(window.startAt(SHANGHAI).toString()).isEqualTo("2026-09-20T04:00+08:00[Asia/Shanghai]");
        assertThat(window.endAt(SHANGHAI).toString()).isEqualTo("2026-09-21T04:00+08:00[Asia/Shanghai]");
    }

    @Test
    void businessDayWindowIsTwentyThreeHoursWhenItCrossesSpringForward() {
        // 美东 2026-03-08 02:00 春进：跨过切换点的营业日（3/7 04:00 → 3/8 04:00）只有 23 小时
        BusinessDayWindow window = StoreTimeService.businessDayWindow(
                LocalDate.of(2026, 3, 7), NEW_YORK, CUTOFF_0400);

        assertThat(window.startInclusive()).isEqualTo(Instant.parse("2026-03-07T09:00:00Z"));
        assertThat(window.endExclusive()).isEqualTo(Instant.parse("2026-03-08T08:00:00Z"));
        assertThat(Duration.between(window.startInclusive(), window.endExclusive())).isEqualTo(Duration.ofHours(23));
    }

    @Test
    void businessDayWindowIsTwentyFiveHoursWhenItCrossesFallBack() {
        // 美东 2026-11-01 02:00 秋回：跨过切换点的营业日（10/31 04:00 → 11/1 04:00）有 25 小时
        BusinessDayWindow window = StoreTimeService.businessDayWindow(
                LocalDate.of(2026, 10, 31), NEW_YORK, CUTOFF_0400);

        assertThat(window.startInclusive()).isEqualTo(Instant.parse("2026-10-31T08:00:00Z"));
        assertThat(window.endExclusive()).isEqualTo(Instant.parse("2026-11-01T09:00:00Z"));
        assertThat(Duration.between(window.startInclusive(), window.endExclusive())).isEqualTo(Duration.ofHours(25));
    }

    @Test
    void businessDayWindowResolvesNonexistentCutoffForwardWithoutThrowing() {
        // 切点 02:30 落在春进当天不存在的墙上时间 → Java 默认解析前移到 03:30 EDT（UTC 07:30）
        BusinessDayWindow window = StoreTimeService.businessDayWindow(
                LocalDate.of(2026, 3, 8), NEW_YORK, LocalTime.of(2, 30));

        assertThat(window.startInclusive()).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
        assertThat(window.endExclusive()).isEqualTo(Instant.parse("2026-03-09T06:30:00Z"));
    }

    @Test
    void businessDayWindowResolvesAmbiguousCutoffToTheEarlierOffsetWithoutThrowing() {
        // 切点 01:30 在秋回当天出现两次 → 起点取回拨前的偏移 (-04:00)，终点在 11/2 用 EST (-05:00)，窗口 25 小时
        BusinessDayWindow window = StoreTimeService.businessDayWindow(
                LocalDate.of(2026, 11, 1), NEW_YORK, LocalTime.of(1, 30));

        assertThat(window.startInclusive()).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        assertThat(window.endExclusive()).isEqualTo(Instant.parse("2026-11-02T06:30:00Z"));
    }

    @Test
    void businessDateRoundTripsWithItsWindowStart() {
        // 窗口起点所在时刻的营业日必须等于窗口自身的营业日（公式自洽）
        BusinessDayWindow window = StoreTimeService.businessDayWindow(
                LocalDate.of(2026, 9, 20), SHANGHAI, CUTOFF_0400);

        assertThat(StoreTimeService.businessDate(window.startInclusive(), SHANGHAI, CUTOFF_0400))
                .isEqualTo(window.businessDate());
    }

    // ------------------------------------------------- 时区校验 TC-U10

    @Test
    void requireZoneIdAcceptsIanaIds() {
        assertThat(StoreTimeService.requireZoneId("Asia/Bangkok")).isEqualTo(BANGKOK);
        assertThat(StoreTimeService.requireZoneId("  America/New_York  ")).isEqualTo(NEW_YORK);
        assertThat(StoreTimeService.requireZoneId("UTC")).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void requireZoneIdRejectsOffsetLiterals() {
        assertThatThrownBy(() -> StoreTimeService.requireZoneId("+08:00"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", 400)
                .hasFieldOrPropertyWithValue("code", StoreTimeService.CODE_TIMEZONE_INVALID);
        assertThatThrownBy(() -> StoreTimeService.requireZoneId("Z"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", StoreTimeService.CODE_TIMEZONE_INVALID);
    }

    @Test
    void requireZoneIdRejectsBlankAndUnknownIds() {
        assertThatThrownBy(() -> StoreTimeService.requireZoneId(null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", StoreTimeService.CODE_TIMEZONE_INVALID);
        assertThatThrownBy(() -> StoreTimeService.requireZoneId("  "))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", StoreTimeService.CODE_TIMEZONE_INVALID);
        assertThatThrownBy(() -> StoreTimeService.requireZoneId("Mars/Olympus"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", StoreTimeService.CODE_TIMEZONE_INVALID);
    }

    @Test
    void requireStoreZoneIdFailsClosedWhenStoreHasNoTimezone() {
        assertThatThrownBy(() -> StoreTimeService.requireStoreZoneId(null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", StoreTimeService.CODE_STORE_TIMEZONE_MISSING)
                .hasFieldOrPropertyWithValue("status", 400);
    }

    // ------------------------------------------------- 时区降级（读路径）

    @Test
    void resolveZoneIdFallsBackStoreThenTenantThenPlatformDefault() {
        assertThat(StoreTimeService.resolveZoneId("Asia/Bangkok", "Asia/Shanghai")).isEqualTo(BANGKOK);
        assertThat(StoreTimeService.resolveZoneId(null, "Asia/Bangkok")).isEqualTo(BANGKOK);
        assertThat(StoreTimeService.resolveZoneId("  ", "  ")).isEqualTo(SHANGHAI);
        assertThat(StoreTimeService.resolveZoneId(null, null)).isEqualTo(SHANGHAI);
    }

    @Test
    void resolveZoneIdDegradesInsteadOfThrowingOnDirtyValues() {
        assertThat(StoreTimeService.resolveZoneId("+08:00", "Asia/Bangkok")).isEqualTo(BANGKOK);
        assertThat(StoreTimeService.resolveZoneId("Mars/Olympus", "not-a-zone")).isEqualTo(SHANGHAI);
    }

    // --------------------------------------------- 营业日切点校验 §3.1

    @Test
    void requireBusinessDayCutoffAcceptsMinuteAndSecondPrecision() {
        assertThat(StoreTimeService.requireBusinessDayCutoff("04:00")).isEqualTo(CUTOFF_0400);
        assertThat(StoreTimeService.requireBusinessDayCutoff("04:00:00")).isEqualTo(CUTOFF_0400);
        assertThat(StoreTimeService.requireBusinessDayCutoff("04:00:59")).isEqualTo(CUTOFF_0400);
        assertThat(StoreTimeService.requireBusinessDayCutoff("00:00")).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(StoreTimeService.requireBusinessDayCutoff("12:00"))
                .isEqualTo(StoreTimeService.MAX_BUSINESS_DAY_CUTOFF);
    }

    @Test
    void requireBusinessDayCutoffRejectsBadFormat() {
        assertThatThrownBy(() -> StoreTimeService.requireBusinessDayCutoff(null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", StoreTimeService.CODE_BUSINESS_DAY_CUTOFF_INVALID);
        assertThatThrownBy(() -> StoreTimeService.requireBusinessDayCutoff("4:00"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", StoreTimeService.CODE_BUSINESS_DAY_CUTOFF_INVALID);
        assertThatThrownBy(() -> StoreTimeService.requireBusinessDayCutoff("abc"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", StoreTimeService.CODE_BUSINESS_DAY_CUTOFF_INVALID);
    }

    @Test
    void requireBusinessDayCutoffRejectsOutOfRange() {
        assertThatThrownBy(() -> StoreTimeService.requireBusinessDayCutoff("12:01"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", StoreTimeService.CODE_BUSINESS_DAY_CUTOFF_OUT_OF_RANGE)
                .hasFieldOrPropertyWithValue("status", 400);
        assertThatThrownBy(() -> StoreTimeService.requireBusinessDayCutoff("23:59"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", StoreTimeService.CODE_BUSINESS_DAY_CUTOFF_OUT_OF_RANGE);
    }

    @Test
    void resolveBusinessDayCutoffDegradesToPlatformDefault() {
        assertThat(StoreTimeService.resolveBusinessDayCutoff(null)).isEqualTo(CUTOFF_0400);
        assertThat(StoreTimeService.resolveBusinessDayCutoff("")).isEqualTo(CUTOFF_0400);
        assertThat(StoreTimeService.resolveBusinessDayCutoff("13:00")).isEqualTo(CUTOFF_0400);
        assertThat(StoreTimeService.resolveBusinessDayCutoff("nonsense")).isEqualTo(CUTOFF_0400);
        assertThat(StoreTimeService.resolveBusinessDayCutoff("06:30")).isEqualTo(LocalTime.of(6, 30));
    }

    @Test
    void formatBusinessDayCutoffUsesMinutePrecision() {
        assertThat(StoreTimeService.formatBusinessDayCutoff(LocalTime.of(4, 0))).isEqualTo("04:00");
        assertThat(StoreTimeService.formatBusinessDayCutoff(LocalTime.of(4, 0, 30))).isEqualTo("04:00");
        assertThat(StoreTimeService.formatBusinessDayCutoff(null)).isEqualTo("04:00");
        assertThat(StoreTimeService.formatBusinessDayCutoff(LocalTime.of(0, 0))).isEqualTo("00:00");
    }
}

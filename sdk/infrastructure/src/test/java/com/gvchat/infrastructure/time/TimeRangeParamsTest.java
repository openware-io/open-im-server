package com.gvchat.infrastructure.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.time.TimeRangeParams.TimeRange;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 后台列表时间区间参数的统一口径守护：闭区间、日期收口、空值不筛、非法即 400。
 *
 * <p>这些断言是「全仓统一约定」的可执行版本：任何域若自行解析 {@code from}/{@code to}，
 * 只要口径与这里不一致就会被本测试挡住（口径写在 {@link TimeRangeParams} 的 javadoc 里）。
 */
class TimeRangeParamsTest {

    @Test
    @DisplayName("两端都为空 → 不筛（不抛异常，也不造出 0 值区间）")
    void emptyParamsMeanNoFilter() {
        TimeRange range = TimeRangeParams.parse(null, null);
        assertThat(range.isEmpty()).isTrue();
        assertThat(range.hasFrom()).isFalse();
        assertThat(range.hasTo()).isFalse();

        assertThat(TimeRangeParams.parse("", "  ").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("只有 from：按闭区间起点解析，to 保持为空")
    void onlyFrom() {
        TimeRange range = TimeRangeParams.parse("2026-09-01", null);
        assertThat(range.fromInclusive()).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0, 0, 0));
        assertThat(range.hasTo()).isFalse();
    }

    @Test
    @DisplayName("只有 to：日期形态收口到当天 23:59:59.999（不是次日零点）")
    void onlyToClosesToEndOfDay() {
        TimeRange range = TimeRangeParams.parse(null, "2026-09-30");
        assertThat(range.toInclusive()).isEqualTo(LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000));
        assertThat(range.hasFrom()).isFalse();
        // 收口值必须仍在当天：用 LocalTime.MAX 会被 MySQL DATETIME(3) 进位到次日。
        assertThat(range.toInclusive().toLocalDate().getDayOfMonth()).isEqualTo(30);
    }

    @Test
    @DisplayName("时刻形态按字面值使用，不再收口")
    void explicitTimeIsTakenLiterally() {
        TimeRange range = TimeRangeParams.parse("2026-09-01T08:30:00", "2026-09-30T20:15:30");
        assertThat(range.fromInclusive()).isEqualTo(LocalDateTime.of(2026, 9, 1, 8, 30, 0));
        assertThat(range.toInclusive()).isEqualTo(LocalDateTime.of(2026, 9, 30, 20, 15, 30));
    }

    @Test
    @DisplayName("兼容空格分隔与带毫秒的形态（后台旧页面按 YYYY-MM-DD HH:mm:ss 传）")
    void acceptsSpaceSeparatedAndMillis() {
        assertThat(TimeRangeParams.parseFrom("2026-09-01 08:30:00"))
                .isEqualTo(LocalDateTime.of(2026, 9, 1, 8, 30, 0));
        assertThat(TimeRangeParams.parseTo("2026-09-30 20:15:30.250"))
                .isEqualTo(LocalDateTime.of(2026, 9, 30, 20, 15, 30, 250_000_000));
    }

    @Test
    @DisplayName("排他上界：日期形态 → 次日 00:00:00（等价「查到该天为止」的左闭右开形态）")
    void exclusiveUpperBoundForDateOnly() {
        assertThat(TimeRangeParams.parseToExclusive("2026-09-30"))
                .isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0, 0, 0));
        // 与闭区间上界的关系：次日零点正好比当天 23:59:59.999 晚 1 毫秒
        assertThat(TimeRangeParams.parseToExclusive("2026-09-30"))
                .isEqualTo(TimeRangeParams.parseTo("2026-09-30").plusNanos(1_000_000));
    }

    @Test
    @DisplayName("排他上界：时刻形态 → 该时刻 + 1ms（DATETIME(3) 下才能包含该毫秒本身）")
    void exclusiveUpperBoundForExplicitTime() {
        assertThat(TimeRangeParams.parseToExclusive("2026-09-30T20:15:30"))
                .isEqualTo(LocalDateTime.of(2026, 9, 30, 20, 15, 30, 1_000_000));
        assertThat(TimeRangeParams.parseToExclusive(null)).isNull();
        assertThat(TimeRangeParams.parseToExclusive("  ")).isNull();
    }

    @Test
    @DisplayName("排他上界同样拒绝非法格式（不能绕过统一错误码）")
    void exclusiveUpperBoundRejectsMalformed() {
        assertThatThrownBy(() -> TimeRangeParams.parseToExclusive("2026/09/30"))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo(TimeRangeParams.CODE_TIME_RANGE_INVALID);
    }

    @Test
    @DisplayName("同一天：from 00:00:00 <= to 23:59:59.999，整天命中")
    void sameDayIsANonEmptyRange() {
        TimeRange range = TimeRangeParams.parse("2026-09-01", "2026-09-01");
        assertThat(range.fromInclusive()).isBefore(range.toInclusive());
    }

    @Test
    @DisplayName("from > to → 400 TIME_RANGE_INVALID，中英提示统一")
    void invertedRangeRejected() {
        assertThatThrownBy(() -> TimeRangeParams.parse("2026-09-30", "2026-09-01"))
                .isInstanceOf(ApiException.class)
                .satisfies(failure -> {
                    ApiException api = (ApiException) failure;
                    assertThat(api.getStatus()).isEqualTo(400);
                    assertThat(api.getCode()).isEqualTo(TimeRangeParams.CODE_TIME_RANGE_INVALID);
                    assertThat(api.getMessage()).isEqualTo(TimeRangeParams.MESSAGE_TIME_RANGE_INVALID);
                });
    }

    @Test
    @DisplayName("同日倒挂的精确到秒也要拒（08:00 之后不能再传 07:00）")
    void invertedSameDayRangeRejected() {
        assertThatThrownBy(() -> TimeRangeParams.parse("2026-09-01T08:00:00", "2026-09-01T07:00:00"))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo(TimeRangeParams.CODE_TIME_RANGE_INVALID);
    }

    @Test
    @DisplayName("格式非法 → 400 TIME_RANGE_INVALID，不静默忽略")
    void malformedRejected() {
        for (String bad : new String[] {"2026/09/01", "2026-9-1", "01-09-2026", "2026-09-01T", "abc", "20260901"}) {
            assertThatThrownBy(() -> TimeRangeParams.parse(bad, null))
                    .as("非法时间参数: %s", bad)
                    .isInstanceOf(ApiException.class)
                    .extracting(failure -> ((ApiException) failure).getCode())
                    .isEqualTo(TimeRangeParams.CODE_TIME_RANGE_INVALID);
        }
    }

    @Test
    @DisplayName("只有一端非法同样 400（不能因为另一端为空就放过）")
    void malformedToOneSideStillRejected() {
        assertThatThrownBy(() -> TimeRangeParams.parse(null, "not-a-date"))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo(TimeRangeParams.CODE_TIME_RANGE_INVALID);
    }

    @Test
    @DisplayName("非法日期（2026-02-30）按格式非法拒绝，不做隐式调整")
    void nonExistentDateRejected() {
        assertThatThrownBy(() -> TimeRangeParams.parse("2026-02-30", null))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo(TimeRangeParams.CODE_TIME_RANGE_INVALID);
    }
}

package io.openware.common.payment.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.common.payment.application.PaymentQueryApplicationService;
import io.openware.common.payment.domain.closing.repository.DailyClosingRepository;
import io.openware.common.payment.domain.collect.repository.PayCollectRepository;
import io.openware.common.payment.domain.intent.repository.PaymentIntentRepository;
import io.openware.common.payment.domain.refund.repository.RefundRepository;
import io.openware.common.payment.domain.shift.repository.ShiftRepository;
import io.openware.common.payment.domain.transaction.repository.PaymentTransactionRepository;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 收银/支付/交班/日结/退款四个只读列表端点的**时间区间**回归。
 *
 * <p>统一契约（{@link TimeRangeParams}）：
 * <ul>
 *   <li>闭区间，日期形态的 {@code to} 收口到当天 {@code 23:59:59.999}（不溢出到次日）；</li>
 *   <li>业务时间列各按其语义：支付/退款 {@code created_at}、交班 {@code opened_at}、
 *       日结营业日 {@code business_date}（{@code LocalDate} 列，取区间的日期边界比较）；</li>
 *   <li>为空 = 不筛（端口收到 null）；</li>
 *   <li>{@code from > to} 或格式非法 → 400 {@code TIME_RANGE_INVALID}，且**不发起查询**。</li>
 * </ul>
 *
 * <p><b>分层后的断言归属</b>：此前本文件断言生成的 SQL 片段（{@code opened_at >=}、不含
 * {@code DATE(}、{@code ORDER BY ... DESC}）。那些是**适配器如何拼查询**的细节，现在归属各适配器测试；
 * 本文件锁定应用层的职责——**归一后的区间实参交到了哪个端口、用哪个时间类型**。
 *
 * <p>「业务时间列各按其语义」这一条在分层后由**端口方法名**承载：
 * {@code listByOpenedAtBetween} / {@code listByBusinessDateBetween} / {@code listByCreatedAtBetween}
 * ——列的选择不再是一段可以写错的 SQL 字符串。
 */
class PaymentTimeRangeFilterTest {

    private static final LocalDateTime CLOSED_END = LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000);

    private final ShiftRepository shiftRepository = mock(ShiftRepository.class);
    private final PaymentIntentRepository paymentIntentRepository = mock(PaymentIntentRepository.class);
    private final DailyClosingRepository dailyClosingRepository = mock(DailyClosingRepository.class);
    private final RefundRepository refundRepository = mock(RefundRepository.class);
    private final PayCollectRepository payCollectRepository = mock(PayCollectRepository.class);
    private final PaymentTransactionRepository paymentTransactionRepository =
            mock(PaymentTransactionRepository.class);

    private final PaymentQueryApplicationService service =
            new PaymentQueryApplicationService(shiftRepository, paymentIntentRepository, dailyClosingRepository,
                    refundRepository, payCollectRepository, paymentTransactionRepository);

    @BeforeEach
    void stubEmptyResults() {
        when(paymentIntentRepository.listByCreatedAtBetween(any(), any())).thenReturn(List.of());
        when(shiftRepository.listByOpenedAtBetween(any(), any())).thenReturn(List.of());
        when(dailyClosingRepository.listByBusinessDateBetween(any(), any())).thenReturn(List.of());
        when(refundRepository.listByCreatedAtBetween(any(), any())).thenReturn(List.of());
    }

    // ------------------------------------------------------------ 支付流水

    @Test
    void paymentsApplyClosedRangeOnCreatedAt() {
        service.payments(TimeRangeParams.parse("2026-09-01", "2026-09-30"));

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(paymentIntentRepository).listByCreatedAtBetween(from.capture(), to.capture());
        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), from.getValue());
        assertEquals(CLOSED_END, to.getValue(), "结束端点必须按当天最后一毫秒收口");
    }

    @Test
    void paymentsWithoutRangeDoNotConstrainCreatedAt() {
        service.payments(TimeRange.none());

        verify(paymentIntentRepository).listByCreatedAtBetween(null, null);
    }

    // ------------------------------------------------------------ 交班

    /** 交班的业务时间是 {@code opened_at}（开班时刻），不是记录写入时间 {@code created_at}。 */
    @Test
    void shiftsFilterOnOpenedAtNotCreatedAt() {
        service.shifts(TimeRangeParams.parse("2026-09-01", "2026-09-30"));

        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(shiftRepository).listByOpenedAtBetween(any(LocalDateTime.class), to.capture());
        assertEquals(CLOSED_END, to.getValue());
        // 列的选择由端口方法名承载（listByOpenedAtBetween）：不会误用 created_at。
        verify(paymentIntentRepository, never()).listByCreatedAtBetween(any(), any());
    }

    // ------------------------------------------------------------ 日结

    /** 营业日是 {@code LocalDate} 列：取区间的日期边界比较，单日区间两端都能命中。 */
    @Test
    void dailyClosingsCompareBusinessDateByDayBoundary() {
        service.dailyClosings(TimeRangeParams.parse("2026-09-30", "2026-09-30"));

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(dailyClosingRepository).listByBusinessDateBetween(from.capture(), to.capture());
        // 营业日没有时刻：两端都是同一个日期，不做时分秒收口（否则单日区间会漏掉自己）。
        assertEquals(LocalDate.of(2026, 9, 30), from.getValue());
        assertEquals(LocalDate.of(2026, 9, 30), to.getValue());
    }

    // ------------------------------------------------------------ 退款

    @Test
    void refundRequestsApplyClosedRangeOnCreatedAt() {
        service.refundRequests(TimeRangeParams.parse(null, "2026-09-30"));

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(refundRepository).listByCreatedAtBetween(from.capture(), to.capture());
        assertEquals(null, from.getValue(), "只传 to 时不得出现下界");
        assertEquals(CLOSED_END, to.getValue());
    }

    // ------------------------------------------------------------ 控制器：解析与错误码

    @Test
    void controllerClosesDayBoundsForPayments() {
        PaymentQueryApplicationService mockService = mock(PaymentQueryApplicationService.class);
        PaymentQueryController controller = new PaymentQueryController(mockService);

        controller.payments("2026-09-01", "2026-09-30");

        ArgumentCaptor<TimeRange> range = ArgumentCaptor.forClass(TimeRange.class);
        verify(mockService).payments(range.capture());
        assertEquals(new TimeRange(LocalDateTime.of(2026, 9, 1, 0, 0, 0, 0), CLOSED_END), range.getValue());
    }

    @Test
    void controllerRejectsInvertedRangeWith400BeforeQuerying() {
        PaymentQueryApplicationService mockService = mock(PaymentQueryApplicationService.class);
        PaymentQueryController controller = new PaymentQueryController(mockService);

        ApiException failure = assertThrows(ApiException.class,
                () -> controller.payments("2026-09-30", "2026-09-01"));

        assertEquals(400, failure.getStatus());
        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
        assertEquals(TimeRangeParams.MESSAGE_TIME_RANGE_INVALID, failure.getMessage());
        verify(mockService, never()).payments(any());
    }

    @Test
    void controllerRejectsMalformedTimeOnEveryListEndpoint() {
        PaymentQueryApplicationService mockService = mock(PaymentQueryApplicationService.class);
        PaymentQueryController controller = new PaymentQueryController(mockService);

        for (String bad : new String[] {"2026/09/01", "20260901", "2026-13-01"}) {
            assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID,
                    assertThrows(ApiException.class, () -> controller.shifts(bad, null)).getCode());
            assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID,
                    assertThrows(ApiException.class, () -> controller.dailyClosings(null, bad)).getCode());
            assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID,
                    assertThrows(ApiException.class, () -> controller.refundRequests(bad, bad)).getCode());
        }
        verifyNoServiceCalls(mockService);
    }

    private static void verifyNoServiceCalls(PaymentQueryApplicationService mockService) {
        verify(mockService, never()).shifts(any());
        verify(mockService, never()).payments(any());
        verify(mockService, never()).dailyClosings(any());
        verify(mockService, never()).refundRequests(any());
    }
}

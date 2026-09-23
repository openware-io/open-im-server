package io.openware.common.payment.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.LambdaUtils;
import io.openware.common.exception.ApiException;
import io.openware.common.payment.application.PaymentQueryApplicationService;
import io.openware.common.payment.infra.persistence.mapper.DailyClosingMapper;
import io.openware.common.payment.infra.persistence.mapper.PayCollectMapper;
import io.openware.common.payment.infra.persistence.mapper.PayIntentMapper;
import io.openware.common.payment.infra.persistence.mapper.PayTransactionMapper;
import io.openware.common.payment.infra.persistence.mapper.RefundMapper;
import io.openware.common.payment.infra.persistence.mapper.ShiftMapper;
import io.openware.common.payment.infra.persistence.po.DailyClosingPo;
import io.openware.common.payment.infra.persistence.po.PayIntentPo;
import io.openware.common.payment.infra.persistence.po.RefundPo;
import io.openware.common.payment.infra.persistence.po.ShiftPo;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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
 *   <li>为空 = 不筛（SQL 里不出现时间条件）；</li>
 *   <li>{@code from > to} 或格式非法 → 400 {@code TIME_RANGE_INVALID}，且**不发起查询**。</li>
 * </ul>
 */
class PaymentTimeRangeFilterTest {

    private static final LocalDateTime CLOSED_END = LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000);

    private final ShiftMapper shiftMapper = mock(ShiftMapper.class);
    private final PayIntentMapper payIntentMapper = mock(PayIntentMapper.class);
    private final DailyClosingMapper dailyClosingMapper = mock(DailyClosingMapper.class);
    private final RefundMapper refundMapper = mock(RefundMapper.class);
    private final PayCollectMapper payCollectMapper = mock(PayCollectMapper.class);
    private final PayTransactionMapper payTransactionMapper = mock(PayTransactionMapper.class);

    private final PaymentQueryApplicationService service =
            new PaymentQueryApplicationService(shiftMapper, payIntentMapper, dailyClosingMapper, refundMapper,
                    payCollectMapper, payTransactionMapper);

    /**
     * lambda → 列名需要 MyBatis-Plus 的 {@code TableInfo}，单元测试里手工初始化；
     * 并显式重装 lambda 缓存（同 JVM 内其它 {@code @SpringBootTest} 关闭上下文时会清掉它）。
     */
    @BeforeEach
    void initTableInfo() {
        for (Class<?> entity : List.of(PayIntentPo.class, ShiftPo.class, DailyClosingPo.class, RefundPo.class)) {
            TableInfo tableInfo = TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
            LambdaUtils.installCache(tableInfo);
        }
    }

    // ------------------------------------------------------------ 支付流水

    @Test
    void paymentsApplyClosedRangeOnCreatedAt() {
        when(payIntentMapper.selectList(any())).thenReturn(List.of());

        service.payments(TimeRangeParams.parse("2026-09-01", "2026-09-30"));

        LambdaQueryWrapper<PayIntentPo> query = capturePayIntent();
        String sql = query.getSqlSegment();
        assertTrue(sql.contains("created_at >="), sql);
        assertTrue(sql.contains("created_at <="), sql);
        assertFalse(sql.contains("DATE("), "时间列不得被函数包裹: " + sql);
        assertTrue(sql.contains("ORDER BY created_at DESC"), "排序口径必须保持倒序: " + sql);
        assertTrue(query.getParamNameValuePairs().values().contains(LocalDateTime.of(2026, 9, 1, 0, 0)), sql);
        assertTrue(query.getParamNameValuePairs().values().contains(CLOSED_END),
                "结束端点必须按当天最后一毫秒收口: " + query.getParamNameValuePairs());
    }

    @Test
    void paymentsWithoutRangeDoNotConstrainCreatedAt() {
        when(payIntentMapper.selectList(any())).thenReturn(List.of());

        service.payments(TimeRange.none());

        LambdaQueryWrapper<PayIntentPo> query = capturePayIntent();
        String sql = query.getSqlSegment();
        assertFalse(sql.contains("created_at >="), "不筛时不得拼出下界: " + sql);
        assertFalse(sql.contains("created_at <="), "不筛时不得拼出上界: " + sql);
        assertTrue(sql.contains("ORDER BY created_at DESC"), sql);
    }

    // ------------------------------------------------------------ 交班

    /** 交班的业务时间是 {@code opened_at}（开班时刻），不是记录写入时间 {@code created_at}。 */
    @Test
    void shiftsFilterOnOpenedAtNotCreatedAt() {
        when(shiftMapper.selectList(any())).thenReturn(List.of());

        service.shifts(TimeRangeParams.parse("2026-09-01", "2026-09-30"));

        LambdaQueryWrapper<ShiftPo> query = captureShift();
        String sql = query.getSqlSegment();
        assertTrue(sql.contains("opened_at >="), "交班区间必须落在 opened_at: " + sql);
        assertTrue(sql.contains("opened_at <="), sql);
        assertFalse(sql.contains("created_at >="), "不得误用 created_at 作为交班时间: " + sql);
        assertTrue(query.getParamNameValuePairs().values().contains(CLOSED_END), sql);
    }

    // ------------------------------------------------------------ 日结

    /** 营业日是 {@code LocalDate} 列：取区间的日期边界比较，单日区间两端都能命中。 */
    @Test
    void dailyClosingsCompareBusinessDateByDayBoundary() {
        when(dailyClosingMapper.selectList(any())).thenReturn(List.of());

        service.dailyClosings(TimeRangeParams.parse("2026-09-30", "2026-09-30"));

        LambdaQueryWrapper<DailyClosingPo> query = captureDailyClosing();
        String sql = query.getSqlSegment();
        assertTrue(sql.contains("business_date >="), sql);
        assertTrue(sql.contains("business_date <="), sql);
        assertTrue(query.getParamNameValuePairs().values().contains(LocalDate.of(2026, 9, 30)),
                "营业日必须按日期边界比较（不是 datetime）: " + query.getParamNameValuePairs());
    }

    // ------------------------------------------------------------ 退款

    @Test
    void refundRequestsApplyClosedRangeOnCreatedAt() {
        when(refundMapper.selectList(any())).thenReturn(List.of());

        service.refundRequests(TimeRangeParams.parse(null, "2026-09-30"));

        LambdaQueryWrapper<RefundPo> query = captureRefund();
        String sql = query.getSqlSegment();
        assertFalse(sql.contains("created_at >="), "只传 to 时不得出现下界: " + sql);
        assertTrue(sql.contains("created_at <="), sql);
        assertTrue(query.getParamNameValuePairs().values().contains(CLOSED_END), sql);
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

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<PayIntentPo> capturePayIntent() {
        ArgumentCaptor<LambdaQueryWrapper<PayIntentPo>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(payIntentMapper).selectList(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<ShiftPo> captureShift() {
        ArgumentCaptor<LambdaQueryWrapper<ShiftPo>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(shiftMapper).selectList(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<DailyClosingPo> captureDailyClosing() {
        ArgumentCaptor<LambdaQueryWrapper<DailyClosingPo>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dailyClosingMapper).selectList(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<RefundPo> captureRefund() {
        ArgumentCaptor<LambdaQueryWrapper<RefundPo>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(refundMapper).selectList(captor.capture());
        return captor.getValue();
    }
}

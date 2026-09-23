package io.openware.platform.order.api.controller;

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
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.order.application.DailySerialNumberGenerator;
import io.openware.platform.order.application.KtvServerSessionApplicationService;
import io.openware.platform.order.application.KtvSessionApplicationService;
import io.openware.platform.order.application.OrderCancellationApplicationService;
import io.openware.platform.order.application.ProductApplicationService;
import io.openware.platform.order.application.ReservationApplicationService;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.infra.persistence.mapper.CustomerLookupMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * order 域四个列表端点的**时间区间**接线回归：订单（下单时间 {@code created_at}）、
 * 商品（创建时间 {@code created_at}）、预约（预约时段 {@code start_at}），
 * 库存物料/流水的时间区间在 {@code InventoryControllerWebTest} 中覆盖。
 *
 * <p>守卫的统一契约（与 {@link TimeRangeParams} 一致）：
 * <ul>
 *   <li>{@code from}/{@code to} 是**闭区间**；日期形态的 {@code from} 收口到当天 {@code 00:00:00.000}、
 *       {@code to} 收口到当天 {@code 23:59:59.999}（不溢出到次日）；</li>
 *   <li>为空 = 不筛（订单列表的 SQL 里干脆不出现 {@code created_at} 的上下界）；</li>
 *   <li>{@code from > to} / 格式非法 → 400 {@code TIME_RANGE_INVALID}，且不落到服务层/SQL；</li>
 *   <li>排序与既有参数不变。</li>
 * </ul>
 */
class TimeRangeListFilterTest {

    private static final long TENANT_ID = 1001L;
    private static final Long STORE_ID = 2001L;
    private static final LocalDateTime CLOSED_END = LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000);

    private final ProductApplicationService productService = mock(ProductApplicationService.class);
    private final ReservationApplicationService reservationService = mock(ReservationApplicationService.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);

    private ProductController productController;
    private ReservationController reservationController;
    private OrderController orderController;

    /**
     * 断言订单列表下发的 SQL 需要把 {@code OrderPo::getCreatedAt} 解析成列名，
     * 而 lambda 缓存来自 MyBatis-Plus 的 {@code TableInfo}：单元测试里没有 MyBatis 启动过程，
     * 必须手工初始化，否则 {@code getSqlSegment()} 会抛「can not find lambda cache」。
     *
     * <p>放在 {@code @BeforeEach} 而不是 {@code @BeforeAll}：同 JVM 内的 {@code @SpringBootTest}
     * 会在上下文关闭时清掉 lambda 缓存，只在类初始化时装一次会随执行顺序偶发失败。
     */
    @BeforeEach
    void initTableInfo() {
        TableInfo tableInfo = TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderPo.class);
        LambdaUtils.installCache(tableInfo);
    }

    @BeforeEach
    void setUp() {
        productController = new ProductController(productService);
        reservationController = new ReservationController(reservationService, mock(CustomerLookupMapper.class));
        orderController = new OrderController(orderMapper, mock(KtvSessionApplicationService.class),
                mock(KtvServerSessionApplicationService.class), mock(ResourceStateClient.class),
                AuditClient.disabled(), mock(OrderCancellationApplicationService.class),
                mock(DailySerialNumberGenerator.class));
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 42L, 1,
                // 含 order.view：订单列表是商户端点（消费者会话会被收窄到本人订单，见 OrderListAuthorizationTest）。
                List.of("product.view", "reservation.view", "order.view")));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    // ------------------------------------------------------------ 商品（创建时间）

    @Test
    void productListPassesClosedDayRangeToService() {
        when(productService.list(any(), any(), any())).thenReturn(List.of());

        productController.list(3L, "ON_SHELF", "2026-09-01", "2026-09-30");

        verify(productService).list(3L, "ON_SHELF",
                new TimeRange(LocalDateTime.of(2026, 9, 1, 0, 0, 0, 0), CLOSED_END));
    }

    @Test
    void productListWithoutTimeParamsMeansNoFilter() {
        when(productService.list(any(), any(), any())).thenReturn(List.of());

        productController.list(3L, "ON_SHELF", null, null);

        ArgumentCaptor<TimeRange> range = ArgumentCaptor.forClass(TimeRange.class);
        verify(productService).list(any(), any(), range.capture());
        assertTrue(range.getValue().isEmpty(), "空参数必须等价于不筛");
    }

    @Test
    void productListRejectsInvertedRangeWith400() {
        ApiException failure = assertThrows(ApiException.class,
                () -> productController.list(null, null, "2026-09-30", "2026-09-01"));

        assertEquals(400, failure.getStatus());
        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
        assertEquals(TimeRangeParams.MESSAGE_TIME_RANGE_INVALID, failure.getMessage());
        verify(productService, never()).list(any(), any(), any());
    }

    @Test
    void productListRejectsMalformedTimeWith400() {
        ApiException failure = assertThrows(ApiException.class,
                () -> productController.list(null, null, "2026/09/01", null));

        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
        verify(productService, never()).list(any(), any(), any());
    }

    // ------------------------------------------------------------ 预约（预约时段 start_at）

    @Test
    void reservationListPassesClosedDayRangeToService() {
        when(reservationService.list(any())).thenReturn(List.of());

        reservationController.list("2026-09-01", "2026-09-30");

        verify(reservationService).list(new TimeRange(LocalDateTime.of(2026, 9, 1, 0, 0, 0, 0), CLOSED_END));
    }

    @Test
    void reservationListRejectsInvertedRangeWith400() {
        ApiException failure = assertThrows(ApiException.class,
                () -> reservationController.list("2026-09-30", "2026-09-01"));

        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
        verify(reservationService, never()).list(any());
    }

    // ------------------------------------------------------------ 订单（下单时间 created_at）

    @Test
    void orderListAppliesClosedRangeOnCreatedAtAndKeepsOrdering() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        orderController.list("2026-09-01", "2026-09-30");

        LambdaQueryWrapper<OrderPo> query = capturedOrderQuery();
        String sql = query.getSqlSegment();
        // 条件落在 created_at 列上（无函数包裹），且有下界与上界
        assertTrue(sql.contains("created_at >="), sql);
        assertTrue(sql.contains("created_at <="), sql);
        assertFalse(sql.contains("DATE("), "时间列不得被函数包裹，否则索引失效: " + sql);
        assertTrue(sql.contains("ORDER BY created_at DESC"), "排序口径必须保持倒序: " + sql);
        assertTrue(query.getParamNameValuePairs().values().contains(LocalDateTime.of(2026, 9, 1, 0, 0)),
                "起始端点必须按当天 00:00:00 收口: " + query.getParamNameValuePairs());
        assertTrue(query.getParamNameValuePairs().values().contains(CLOSED_END),
                "结束端点必须按当天最后一毫秒收口（闭区间）: " + query.getParamNameValuePairs());
    }

    @Test
    void orderListWithOnlyFromLeavesUpperBoundOpen() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        orderController.list("2026-09-01", null);

        LambdaQueryWrapper<OrderPo> query = capturedOrderQuery();
        String sql = query.getSqlSegment();
        assertTrue(sql.contains("created_at >="), sql);
        assertFalse(sql.contains("created_at <="), "只传 from 时不得出现上界: " + sql);
    }

    @Test
    void orderListWithoutTimeParamsDoesNotConstrainCreatedAt() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        orderController.list(null, null);

        LambdaQueryWrapper<OrderPo> query = capturedOrderQuery();
        String sql = query.getSqlSegment();
        assertFalse(sql.contains("created_at >="), "不筛时不得拼出时间条件: " + sql);
        assertFalse(sql.contains("created_at <="), "不筛时不得拼出时间条件: " + sql);
        assertTrue(sql.contains("ORDER BY created_at DESC"), "不筛时排序不变: " + sql);
    }

    @Test
    void orderListRejectsInvertedRangeWith400BeforeTouchingMapper() {
        ApiException failure = assertThrows(ApiException.class,
                () -> orderController.list("2026-09-30", "2026-09-01"));

        assertEquals(400, failure.getStatus());
        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
        // 非法区间不得发出任何查询
        verify(orderMapper, never()).selectList(any());
    }

    @Test
    void orderListRejectsMalformedTimeWith400() {
        ApiException failure = assertThrows(ApiException.class, () -> orderController.list("20260901", null));

        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
        verify(orderMapper, never()).selectList(any());
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<OrderPo> capturedOrderQuery() {
        ArgumentCaptor<LambdaQueryWrapper<OrderPo>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper).selectList(captor.capture());
        return captor.getValue();
    }
}

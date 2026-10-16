package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import io.openware.platform.order.domain.order.port.MemberLookupProvider;
import io.openware.platform.order.domain.order.repository.OrderQuery;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.model.Order;
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
    private final OrderRepository orderMapper = mock(OrderRepository.class);

    private ProductController productController;
    private ReservationController reservationController;
    private OrderController orderController;

    /**
     * 订单列表的时间区间断言口径（端口化后）：条件收在 {@link OrderQuery} 里，
     * 因此这里断言传给仓储的查询对象（下界/上界/排序），
     * **SQL 形状**（{@code created_at >=}/{@code <=}、不被函数包裹、{@code ORDER BY created_at DESC}）
     * 由 {@code io.openware.platform.order.infra.persistence.repository.OrderRepositoryAdapterTest} 钉住。
     */
    @BeforeEach
    void setUp() {
        productController = new ProductController(productService);
        reservationController = new ReservationController(reservationService, mock(MemberLookupProvider.class));
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
        when(orderMapper.list(any())).thenReturn(List.of());

        orderController.list("2026-09-01", "2026-09-30");

        OrderQuery query = capturedOrderQuery();
        // 闭区间：下界按当天 00:00:00、上界按当天最后一毫秒收口（比较符形状见 OrderRepositoryAdapterTest）
        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), query.createdAtFromInclusive(),
                "起始端点必须按当天 00:00:00 收口");
        assertEquals(CLOSED_END, query.createdAtToInclusive(),
                "结束端点必须按当天最后一毫秒收口（闭区间）");
        assertEquals(OrderQuery.Ordering.CREATED_AT_DESC, query.ordering(), "排序口径必须保持倒序");
    }

    @Test
    void orderListWithOnlyFromLeavesUpperBoundOpen() {
        when(orderMapper.list(any())).thenReturn(List.of());

        orderController.list("2026-09-01", null);

        OrderQuery query = capturedOrderQuery();
        assertNotNull(query.createdAtFromInclusive(), "只传 from 时下界必须下发");
        assertNull(query.createdAtToInclusive(), "只传 from 时不得出现上界");
    }

    @Test
    void orderListWithoutTimeParamsDoesNotConstrainCreatedAt() {
        when(orderMapper.list(any())).thenReturn(List.of());

        orderController.list(null, null);

        OrderQuery query = capturedOrderQuery();
        assertNull(query.createdAtFromInclusive(), "不筛时不得下发时间下界");
        assertNull(query.createdAtToInclusive(), "不筛时不得下发时间上界");
        assertNull(query.createdAtBefore(), "不筛时不得下发半开上界");
        assertEquals(OrderQuery.Ordering.CREATED_AT_DESC, query.ordering(), "不筛时排序不变");
    }

    @Test
    void orderListRejectsInvertedRangeWith400BeforeTouchingMapper() {
        ApiException failure = assertThrows(ApiException.class,
                () -> orderController.list("2026-09-30", "2026-09-01"));

        assertEquals(400, failure.getStatus());
        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
        // 非法区间不得发出任何查询
        verify(orderMapper, never()).list(any());
    }

    @Test
    void orderListRejectsMalformedTimeWith400() {
        ApiException failure = assertThrows(ApiException.class, () -> orderController.list("20260901", null));

        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
        verify(orderMapper, never()).list(any());
    }

    private OrderQuery capturedOrderQuery() {
        ArgumentCaptor<OrderQuery> captor = ArgumentCaptor.forClass(OrderQuery.class);
        verify(orderMapper).list(captor.capture());
        return captor.getValue();
    }
}

package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.DailySerialNumberGenerator;
import io.openware.platform.order.application.KtvServerSessionApplicationService;
import io.openware.platform.order.application.KtvSessionApplicationService;
import io.openware.platform.order.application.OrderCancellationApplicationService;
import io.openware.platform.order.application.dto.OrderView;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.infra.client.ResourceStateClient;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@code /business/orders/**} 的路由级行为：建单必须走统一单号生成器。
 *
 * <p>订单级挂单/解挂端点已删除（挂单由 {@code ord_ktv_session.status=PAUSED} 表达，
 * 见 {@code KtvSessionApplicationService#pause/#resume}），本类只覆盖建单路由。
 */
class OrderControllerAuditTest {

    private static final Long ORDER_ID = 88L;
    private static final long TENANT_ID = 1001L;
    private static final Long STORE_ID = 2001L;

    private OrderRepository orderMapper;
    private OrderCancellationApplicationService orderCancellationService;
    private KtvSessionApplicationService ktvSessionService;
    private AuditClient auditClient;
    private DailySerialNumberGenerator dailySerialNumberGenerator;
    private OrderController controller;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderRepository.class);
        orderCancellationService = mock(OrderCancellationApplicationService.class);
        ktvSessionService = mock(KtvSessionApplicationService.class);
        auditClient = mock(AuditClient.class);
        dailySerialNumberGenerator = mock(DailySerialNumberGenerator.class);
        controller = OrderControllerTestFactory.create(orderMapper, ktvSessionService,
                mock(KtvServerSessionApplicationService.class), mock(ResourceStateClient.class), auditClient,
                orderCancellationService, dailySerialNumberGenerator);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /**
     * 快速开台建单（{@code POST /business/orders}）必须走统一单号生成器：
     * {@code O<yyyyMMdd><当日序号>}，不再是 {@code "O" + System.currentTimeMillis()}。
     */
    @Test
    void createOrder_usesDailySerialOrderNumber() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 42L, 1,
                List.of("ktv.session.open")));
        when(dailySerialNumberGenerator.next(DailySerialNumberGenerator.DocType.ORDER, TENANT_ID))
                .thenReturn("O202609190001");
        when(orderMapper.insert(any(Order.class), any())).thenAnswer(inv -> ((Order) inv.getArgument(0)).withId(7L));

        OrderView created = controller.createOrder(null,
                new OrderController.CreateOrderRequest(null, null, null, "RETAIL", null, null));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(captor.capture(), any());
        assertEquals("O202609190001", captor.getValue().orderNo());
        assertNull(captor.getValue().idempotencyKey(), "不带 Idempotency-Key 时不得写入幂等键");
        assertEquals("O202609190001", created.orderNo());
    }

    /** 夹具：领域模型不可变，用规范构造器一次成型（审计列不在模型上）。 */
    @SuppressWarnings("unused")
    private static Order order(String status) {
        return new Order(ORDER_ID, TENANT_ID, null, STORE_ID, "ORD-20260918-0001", null, "KTV", null, status,
                "NONE", "CNY", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, 0);
    }
}

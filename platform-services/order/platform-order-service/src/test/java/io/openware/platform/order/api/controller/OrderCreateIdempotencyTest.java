package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.infrastructure.audit.AuditClient;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.DailySerialNumberGenerator;
import io.openware.platform.order.application.KtvServerSessionApplicationService;
import io.openware.platform.order.application.KtvSessionApplicationService;
import io.openware.platform.order.application.OrderCancellationApplicationService;
import io.openware.platform.order.domain.ktv.model.KtvSession;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.api.dto.OrderView;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.model.Order;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/**
 * 快速开台幂等回归（2026-09-19）：`POST /business/orders` 此前没有幂等键，
 * 而 B 端 App 的写请求拦截器**每次都会带 Idempotency-Key** —— 服务端忽略它，
 * 一次网络重试/重复点击就多一张 DRAFT 单（房态看板同一包厢两张「使用中」订单）。
 *
 * <p>修后口径：带键且命中已有订单 → 回放该订单（含 sessionId），不建单、不建房态会话；
 * 不带键 → 行为完全不变；并发重复提交被唯一键挡下后同样回放先写入的那张单。
 */
class OrderCreateIdempotencyTest {

    private static final long TENANT_ID = 100L;
    private static final long STORE_ID = 55L;

    private final OrderRepository orderMapper = mock(OrderRepository.class);
    private final KtvSessionApplicationService ktvSessionService = mock(KtvSessionApplicationService.class);
    private final DailySerialNumberGenerator serialGenerator = mock(DailySerialNumberGenerator.class);
    private final OrderController controller = new OrderController(
            orderMapper,
            ktvSessionService,
            mock(KtvServerSessionApplicationService.class),
            mock(ResourceStateClient.class),
            AuditClient.disabled(),
            mock(OrderCancellationApplicationService.class),
            serialGenerator,
            null);

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /** 不带 Idempotency-Key：行为与改造前一致，幂等键列写 NULL。 */
    @Test
    void withoutKeyCreatesOrderAsBefore() {
        merchantContext();
        when(serialGenerator.next(DailySerialNumberGenerator.DocType.ORDER, TENANT_ID)).thenReturn("O202609190001");
        when(orderMapper.insert(any(Order.class), any())).thenAnswer(inv -> ((Order) inv.getArgument(0)).withId(11L));

        OrderView created = controller.createOrder(null,
                new OrderController.CreateOrderRequest(null, null, null, "RETAIL", null, null));

        assertEquals("O202609190001", created.orderNo());
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(captor.capture(), any());
        assertNull(captor.getValue().idempotencyKey());
    }

    /** 带键：首次建单把键落到订单上（唯一键 (tenant_id, idempotency_key) 才能兜底并发）。 */
    @Test
    void firstCallStoresIdempotencyKey() {
        merchantContext();
        when(orderMapper.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(serialGenerator.next(DailySerialNumberGenerator.DocType.ORDER, TENANT_ID)).thenReturn("O202609190002");
        when(orderMapper.insert(any(Order.class), any())).thenAnswer(inv -> ((Order) inv.getArgument(0)).withId(12L));

        controller.createOrder("idem-abc",
                new OrderController.CreateOrderRequest(null, null, null, "RETAIL", null, null));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(captor.capture(), any());
        assertEquals("idem-abc", captor.getValue().idempotencyKey());
    }

    /** 重试（同键且已有订单）：回放既有订单（补 sessionId），**不再建单**、不再建房态会话。 */
    @Test
    void retryReplaysExistingOrderAndSkipsCreation() {
        merchantContext();
        Order existing = order(88L, "O202609190003");
        when(orderMapper.findByIdempotencyKey(any(), any())).thenReturn(Optional.of(existing));
        // 已有会话（领域模型不可变：工厂造出后回填主键），回放必须把它带出来。
        KtvSession session = KtvSession.reserved(TENANT_ID, STORE_ID, "KTV", 88L, 3001L, null, null)
                .withId(555L);
        when(ktvSessionService.listByOrderIds(any())).thenReturn(java.util.Map.of(88L, session));

        OrderView replayed = controller.createOrder("idem-abc",
                new OrderController.CreateOrderRequest(null, null, null, "KTV", null, 3001L));

        assertEquals(88L, replayed.id());
        assertEquals(555L, replayed.sessionId(), "回放必须带会话 id，否则客户端会以为「没有会话」");
        verify(orderMapper, never()).insert(any(Order.class), any());
        verify(ktvSessionService, never()).create(any(), any(), any());
    }

    /**
     * 没有会话的订单（非 KTV 单 / 建单时未选包厢）同样能幂等回放：不能因为「查会话找不到」变成 404。
     * 这条口径是端到端验证时发现的真实缺陷（初版回放用了 findByOrderId，缺会话即抛 ORDER_NOT_FOUND）。
     */
    @Test
    void retryReplaysOrderWithoutSession() {
        merchantContext();
        Order existing = order(89L, "O202609190004");
        when(orderMapper.findByIdempotencyKey(any(), any())).thenReturn(Optional.of(existing));
        when(ktvSessionService.listByOrderIds(any())).thenReturn(java.util.Map.of());

        OrderView replayed = controller.createOrder("idem-xyz",
                new OrderController.CreateOrderRequest(null, null, null, "RETAIL", null, null));

        assertEquals(89L, replayed.id());
        assertNull(replayed.sessionId());
        verify(orderMapper, never()).insert(any(Order.class), any());
    }

    /** 并发重复提交：第二个请求被唯一键挡下（DuplicateKeyException）→ 回放先写入的那张单。 */
    @Test
    void concurrentDuplicateReplaysWinner() {
        merchantContext();
        Order winner = order(99L, "O202609190004");
        // 第一次查询（插入前）没有、插入撞唯一键、插入后再查命中
        when(orderMapper.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty(), Optional.of(winner));
        when(serialGenerator.next(DailySerialNumberGenerator.DocType.ORDER, TENANT_ID)).thenReturn("O202609190005");
        when(orderMapper.insert(any(Order.class), any())).thenThrow(new DuplicateKeyException("uk_ord_order_idem"));
        when(ktvSessionService.listByOrderIds(any())).thenReturn(java.util.Map.of());

        OrderView replayed = controller.createOrder("idem-abc",
                new OrderController.CreateOrderRequest(null, null, null, "RETAIL", null, null));

        assertEquals(99L, replayed.id());
    }

    /** 订单夹具：领域模型不可变，用规范构造器一次成型（审计列不在模型上）。 */
    private static Order order(Long id, String orderNo) {
        return new Order(id, TENANT_ID, 1L, STORE_ID, orderNo, null, "KTV", null, "DRAFT", "CNY",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, null, null, 0);
    }

    /** 门店上下文业态是权威值，请求体伪造其它业态必须在落单前拒绝。 */
    @Test
    void rejectsBusinessTypeDifferentFromStoreContext() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 42L, 1,
                List.of("ktv.session.open"), "TENANT", "KTV", "Asia/Shanghai"));

        ApiException failure = assertThrows(ApiException.class,
                () -> controller.createOrder(null,
                        new OrderController.CreateOrderRequest(null, null, null, "RETAIL", null, null)));

        assertEquals("BUSINESS_TYPE_MISMATCH", failure.getCode());
        verify(orderMapper, never()).insert(any(Order.class), any());
    }

    private static void merchantContext() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 42L, 1,
                List.of("ktv.session.open")));
    }
}

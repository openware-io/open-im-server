package com.gvchat.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.application.DailySerialNumberGenerator;
import com.gvchat.platform.order.application.KtvServerSessionApplicationService;
import com.gvchat.platform.order.application.KtvSessionApplicationService;
import com.gvchat.platform.order.application.OrderCancellationApplicationService;
import com.gvchat.platform.order.infra.client.ResourceStateClient;
import com.gvchat.platform.order.infra.persistence.mapper.OrderMapper;
import com.gvchat.platform.order.infra.persistence.po.KtvSessionPo;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
import java.util.List;
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

    private final OrderMapper orderMapper = mock(OrderMapper.class);
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
        when(orderMapper.insert(any(OrderPo.class))).thenReturn(1);

        OrderPo created = controller.createOrder(null,
                new OrderController.CreateOrderRequest(null, null, null, "RETAIL", null, null));

        assertEquals("O202609190001", created.getOrderNo());
        ArgumentCaptor<OrderPo> captor = ArgumentCaptor.forClass(OrderPo.class);
        verify(orderMapper).insert(captor.capture());
        assertNull(captor.getValue().getIdempotencyKey());
    }

    /** 带键：首次建单把键落到订单上（唯一键 (tenant_id, idempotency_key) 才能兜底并发）。 */
    @Test
    void firstCallStoresIdempotencyKey() {
        merchantContext();
        when(orderMapper.selectOne(any())).thenReturn(null);
        when(serialGenerator.next(DailySerialNumberGenerator.DocType.ORDER, TENANT_ID)).thenReturn("O202609190002");
        when(orderMapper.insert(any(OrderPo.class))).thenReturn(1);

        controller.createOrder("idem-abc",
                new OrderController.CreateOrderRequest(null, null, null, "RETAIL", null, null));

        ArgumentCaptor<OrderPo> captor = ArgumentCaptor.forClass(OrderPo.class);
        verify(orderMapper).insert(captor.capture());
        assertEquals("idem-abc", captor.getValue().getIdempotencyKey());
    }

    /** 重试（同键且已有订单）：回放既有订单（补 sessionId），**不再建单**、不再建房态会话。 */
    @Test
    void retryReplaysExistingOrderAndSkipsCreation() {
        merchantContext();
        OrderPo existing = new OrderPo();
        existing.setId(88L);
        existing.setTenantId(TENANT_ID);
        existing.setOrderNo("O202609190003");
        when(orderMapper.selectOne(any())).thenReturn(existing);
        KtvSessionPo session = new KtvSessionPo();
        session.setId(555L);
        when(ktvSessionService.listByOrderIds(any())).thenReturn(java.util.Map.of(88L, session));

        OrderPo replayed = controller.createOrder("idem-abc",
                new OrderController.CreateOrderRequest(null, null, null, "KTV", null, 3001L));

        assertEquals(88L, replayed.getId());
        assertEquals(555L, replayed.getSessionId(), "回放必须带会话 id，否则客户端会以为「没有会话」");
        verify(orderMapper, never()).insert(any(OrderPo.class));
        verify(ktvSessionService, never()).create(any(), any(), any());
    }

    /**
     * 没有会话的订单（非 KTV 单 / 建单时未选包厢）同样能幂等回放：不能因为「查会话找不到」变成 404。
     * 这条口径是端到端验证时发现的真实缺陷（初版回放用了 findByOrderId，缺会话即抛 ORDER_NOT_FOUND）。
     */
    @Test
    void retryReplaysOrderWithoutSession() {
        merchantContext();
        OrderPo existing = new OrderPo();
        existing.setId(89L);
        existing.setOrderNo("O202609190004");
        when(orderMapper.selectOne(any())).thenReturn(existing);
        when(ktvSessionService.listByOrderIds(any())).thenReturn(java.util.Map.of());

        OrderPo replayed = controller.createOrder("idem-xyz",
                new OrderController.CreateOrderRequest(null, null, null, "RETAIL", null, null));

        assertEquals(89L, replayed.getId());
        assertNull(replayed.getSessionId());
        verify(orderMapper, never()).insert(any(OrderPo.class));
    }

    /** 并发重复提交：第二个请求被唯一键挡下（DuplicateKeyException）→ 回放先写入的那张单。 */
    @Test
    void concurrentDuplicateReplaysWinner() {
        merchantContext();
        OrderPo winner = new OrderPo();
        winner.setId(99L);
        winner.setOrderNo("O202609190004");
        // 第一次查询（插入前）没有、插入撞唯一键、插入后再查命中
        when(orderMapper.selectOne(any())).thenReturn(null, winner);
        when(serialGenerator.next(DailySerialNumberGenerator.DocType.ORDER, TENANT_ID)).thenReturn("O202609190005");
        when(orderMapper.insert(any(OrderPo.class))).thenThrow(new DuplicateKeyException("uk_ord_order_idem"));
        when(ktvSessionService.listByOrderIds(any())).thenReturn(java.util.Map.of());

        OrderPo replayed = controller.createOrder("idem-abc",
                new OrderController.CreateOrderRequest(null, null, null, "RETAIL", null, null));

        assertEquals(99L, replayed.getId());
    }

    private static void merchantContext() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 42L, 1,
                List.of("ktv.session.open")));
    }
}

package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.DailySerialNumberGenerator;
import io.openware.platform.order.application.KtvServerSessionApplicationService;
import io.openware.platform.order.application.KtvSessionApplicationService;
import io.openware.platform.order.application.OrderCancellationApplicationService;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.domain.order.port.MemberLookupProvider;
import io.openware.platform.order.domain.order.repository.OrderQuery;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 订单列表授权回归（2026-09-19）：旧实现 `GET /business/orders` 既没有权限码也没有归属收窄，
 * 任意登录的消费者会话都能拉到本租户**全部订单**（范围比已修的账单端点更大）。
 *
 * <p>修后口径：持 {@code order.view} → 行为不变（不加 customer_id 条件）；
 * 消费者会话 → 按 {@code ord_order.customer_id} 收窄到本人；
 * 解析不出会员档案 → 403 {@code PERMISSION_DENIED}（与账单端点同文案）；
 * 无签名上下文（内部调用/单测装配）→ 保持既有行为。
 *
 * <p><b>断言口径</b>：查询条件现在收在 {@link OrderQuery} 端口对象里，
 * 因此这里断言的是「传给仓储的查询对象」（是否收窄、是否带时间窗），
 * 而**SQL 形状**（`customer_id` 落在哪一列、`created_at >=/<=`、`ORDER BY created_at DESC`）
 * 由 {@code io.openware.platform.order.infra.persistence.repository.OrderRepositoryAdapterTest} 钉住。
 */
class OrderListAuthorizationTest {

    private static final long TENANT_ID = 100L;
    private static final long ACCOUNT_ID = 7L;
    private static final long MEMBER_ID = 77L;

    private final OrderRepository orderMapper = mock(OrderRepository.class);
    private final MemberLookupProvider customerLookupMapper = mock(MemberLookupProvider.class);
    private final OrderController controller = new OrderController(
            orderMapper,
            mock(KtvSessionApplicationService.class),
            mock(KtvServerSessionApplicationService.class),
            mock(ResourceStateClient.class),
            AuditClient.disabled(),
            mock(OrderCancellationApplicationService.class),
            mock(DailySerialNumberGenerator.class),
            customerLookupMapper);

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /** B 端（持 order.view）：不查归属、不加条件，行为与改造前完全一致。 */
    @Test
    void merchantSessionListsWholeTenantWithoutCustomerScope() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1,
                List.of("order.view")));
        when(orderMapper.list(any())).thenReturn(List.of());

        controller.list(null, null);

        OrderQuery query = capturedQuery();
        assertNull(query.customerId(), "商户列表不得被收窄到某个客户");
        assertEquals(OrderQuery.Ordering.CREATED_AT_DESC, query.ordering(),
                "订单列表按创建时间倒序（SQL 形状见 OrderRepositoryAdapterTest）");
        verify(customerLookupMapper, never()).findMemberId(anyLong(), anyLong());
    }

    /** 消费者会话：按 customer_id 收窄到本人（C 端会话不再能看到同租户他人订单）。 */
    @Test
    void consumerSessionIsScopedToOwnCustomerId() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1,
                List.of("reservation.view")));
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(Optional.of(MEMBER_ID));
        when(orderMapper.list(any())).thenReturn(List.of());

        controller.list(null, null);

        assertEquals(MEMBER_ID, capturedQuery().customerId(),
                "customer_id 取值必须是签名上下文对应的会员");
    }

    /** 时间区间与归属收窄共存：两个条件都要在（避免修一处漏一处）。 */
    @Test
    void consumerScopeKeepsTimeRangeFilters() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1,
                List.of("reservation.view")));
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(Optional.of(MEMBER_ID));
        when(orderMapper.list(any())).thenReturn(List.of());

        controller.list("2026-09-01", "2026-09-30");

        OrderQuery query = capturedQuery();
        assertEquals(MEMBER_ID, query.customerId());
        assertNotNull(query.createdAtFromInclusive(), "闭区间下界必须落到查询对象上");
        assertNotNull(query.createdAtToInclusive(), "闭区间上界必须落到查询对象上");
    }

    /** 既无商户权限也解析不出会员：403 PERMISSION_DENIED，且根本不查订单表。 */
    @Test
    void consumerWithoutMemberProfileIsRejectedBeforeQuerying() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1,
                List.of("reservation.view")));
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> controller.list(null, null));

        assertEquals(403, ex.getStatus());
        assertEquals("PERMISSION_DENIED", ex.getCode());
        verify(orderMapper, never()).list(any());
    }

    /** 无签名上下文（服务间内部调用/单测装配）：保持既有行为，不额外收窄也不拦。 */
    @Test
    void missingContextKeepsLegacyBehaviour() {
        when(orderMapper.list(any())).thenReturn(List.of());

        controller.list(null, null);

        assertNull(capturedQuery().customerId());
    }

    private OrderQuery capturedQuery() {
        ArgumentCaptor<OrderQuery> captor = ArgumentCaptor.forClass(OrderQuery.class);
        verify(orderMapper).list(captor.capture());
        return captor.getValue();
    }
}

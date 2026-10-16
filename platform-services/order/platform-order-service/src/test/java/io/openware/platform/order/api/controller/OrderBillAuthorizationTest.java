package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.BillApplicationService;
import io.openware.platform.order.application.dto.BillResult;
import io.openware.platform.order.domain.order.repository.MemberLookupProvider;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.model.Order;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 账单端点授权回归（2026-09-19）：旧实现既无权限码也无归属校验，
 * 任意登录消费者换一个订单 ID 就能读同租户**他人订单**账单（IDOR）。
 *
 * <p>修后口径：持 {@code order.view} → 行为不变；消费者会话 → 只允许本人订单，
 * 他人订单 403 {@code ORDER_SCOPE_DENIED}，解析不出会员 403 {@code PERMISSION_DENIED}。
 */
class OrderBillAuthorizationTest {

    private static final long TENANT_ID = 100L;
    private static final long ACCOUNT_ID = 7L;

    private final BillApplicationService billService = mock(BillApplicationService.class);
    private final OrderRepository orderMapper = mock(OrderRepository.class);
    private final MemberLookupProvider customerLookupMapper = mock(MemberLookupProvider.class);
    private final OrderBillController controller =
            new OrderBillController(billService, orderMapper, customerLookupMapper);

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /** B 端（持 order.view）：不查归属、行为与改造前完全一致。 */
    @Test
    void merchantSessionReadsAnyOrderBill() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1,
                List.of("order.view")));
        BillResult expected = bill();
        when(billService.buildBill(9L)).thenReturn(expected);

        assertEquals(expected, controller.bill(9L));
        verify(orderMapper, never()).findById(any());
    }

    /** 消费者读**本人**订单账单：放行（C 端「查看账单」正常路径）。 */
    @Test
    void consumerSessionReadsOwnOrderBill() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1,
                List.of("reservation.view")));
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(Optional.of(77L));
        Order own = order(9L, 77L);
        when(orderMapper.findById(9L)).thenReturn(Optional.of(own));
        BillResult expected = bill();
        when(billService.buildBill(9L)).thenReturn(expected);

        assertEquals(expected, controller.bill(9L));
    }

    /** 一个最小可用的账单对象：record 不 mock，避免依赖 Mockito 对 final/record 的支持。 */
    private static BillResult bill() {
        return new BillResult("SERVING", "CNY", null, List.of(), List.of(), List.of(),
                0L, 0L, 0L, 0L, 0L, 0L, null, 0L);
    }

    /** 订单夹具：领域模型不可变，用规范构造器一次成型（审计列不在模型上）。 */
    private static Order order(Long id, Long customerId) {
        return new Order(id, TENANT_ID, null, null, "ORD-20260918-0001", null, "KTV", customerId, "SERVING",
                "CNY", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, null, null, 0);
    }

    /** 消费者读**他人**订单账单：403 ORDER_SCOPE_DENIED，且根本不进账单服务（不泄露任何账单内容）。 */
    @Test
    void consumerSessionCannotReadOthersBill() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1,
                List.of("reservation.view")));
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(Optional.of(77L));
        Order others = order(9L, 88L);
        when(orderMapper.findById(9L)).thenReturn(Optional.of(others));

        ApiException ex = assertThrows(ApiException.class, () -> controller.bill(9L));

        assertEquals(403, ex.getStatus());
        assertEquals("ORDER_SCOPE_DENIED", ex.getCode());
        verify(billService, never()).buildBill(any());
    }

    /** 既无商户权限也解析不出会员：保持既有 403 PERMISSION_DENIED 文案（不新增可探测信息面）。 */
    @Test
    void consumerWithoutMemberProfileKeepsLegacyPermissionDenied() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1,
                List.of("reservation.view")));
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> controller.bill(9L));

        assertEquals(403, ex.getStatus());
        assertEquals("PERMISSION_DENIED", ex.getCode());
        verify(billService, never()).buildBill(any());
    }
}

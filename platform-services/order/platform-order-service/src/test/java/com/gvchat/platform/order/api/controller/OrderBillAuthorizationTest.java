package com.gvchat.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.application.BillApplicationService;
import com.gvchat.platform.order.application.dto.BillResult;
import com.gvchat.platform.order.infra.persistence.mapper.CustomerLookupMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderMapper;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
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
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final CustomerLookupMapper customerLookupMapper = mock(CustomerLookupMapper.class);
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
        verify(orderMapper, never()).selectById(any());
    }

    /** 消费者读**本人**订单账单：放行（C 端「查看账单」正常路径）。 */
    @Test
    void consumerSessionReadsOwnOrderBill() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1,
                List.of("reservation.view")));
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(77L);
        OrderPo own = new OrderPo();
        own.setId(9L);
        own.setCustomerId(77L);
        when(orderMapper.selectById(9L)).thenReturn(own);
        BillResult expected = bill();
        when(billService.buildBill(9L)).thenReturn(expected);

        assertEquals(expected, controller.bill(9L));
    }

    /** 一个最小可用的账单对象：record 不 mock，避免依赖 Mockito 对 final/record 的支持。 */
    private static BillResult bill() {
        return new BillResult("SERVING", "CNY", null, List.of(), List.of(), List.of(),
                0L, 0L, 0L, 0L, 0L, 0L, null, 0L);
    }

    /** 消费者读**他人**订单账单：403 ORDER_SCOPE_DENIED，且根本不进账单服务（不泄露任何账单内容）。 */
    @Test
    void consumerSessionCannotReadOthersBill() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1,
                List.of("reservation.view")));
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(77L);
        OrderPo others = new OrderPo();
        others.setId(9L);
        others.setCustomerId(88L);
        when(orderMapper.selectById(9L)).thenReturn(others);

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
        when(customerLookupMapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class, () -> controller.bill(9L));

        assertEquals(403, ex.getStatus());
        assertEquals("PERMISSION_DENIED", ex.getCode());
        verify(billService, never()).buildBill(any());
    }
}

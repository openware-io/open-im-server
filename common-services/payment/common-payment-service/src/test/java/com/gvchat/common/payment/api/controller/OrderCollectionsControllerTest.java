package com.gvchat.common.payment.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.payment.application.OrderCollectionsDto;
import com.gvchat.common.payment.application.PaymentQueryApplicationService;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 订单收款明细端点的**访问边界**回归：
 * <ul>
 *   <li>必须要求 {@code order.view}：这是按订单查资金的读接口，不能让 C 端消费者令牌凭 orderId 猜读他人收款明细；</li>
 *   <li>缺少租户上下文 → 401（租户隔离的前提）；</li>
 *   <li>{@code orderIds} 为空 → 400；超过上限 → 400 且**不查库**（避免一次拉全租户资金明细）。</li>
 * </ul>
 */
class OrderCollectionsControllerTest {

    private static final Long TENANT_ID = 100L;

    private final PaymentQueryApplicationService service = mock(PaymentQueryApplicationService.class);
    private final PaymentQueryController controller = new PaymentQueryController(service);

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    void requiresOrderViewPermission() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, 100L, 5L, 1, List.of("payment.collect")));

        ApiException error = assertThrows(ApiException.class,
                () -> controller.orderCollections(List.of(69L)));

        assertEquals(403, error.getStatus());
        assertEquals("PERMISSION_DENIED", error.getCode());
        verify(service, never()).orderCollections(any(), anyList());
    }

    @Test
    void requiresTenantContext() {
        TenantContextHolder.set(new TenantContext(0L, null, null, 5L, 1, List.of("order.view")));

        ApiException error = assertThrows(ApiException.class,
                () -> controller.orderCollections(List.of(69L)));

        assertEquals(401, error.getStatus());
        assertEquals("SAAS_CONTEXT_REQUIRED", error.getCode());
    }

    @Test
    void rejectsEmptyOrderIds() {
        TenantContextHolder.set(tenantWithOrderView());

        ApiException error = assertThrows(ApiException.class, () -> controller.orderCollections(List.of()));

        assertEquals(400, error.getStatus());
        assertEquals("ORDER_IDS_REQUIRED", error.getCode());
        verify(service, never()).orderCollections(any(), anyList());
    }

    @Test
    void rejectsTooManyOrderIdsWithoutQuerying() {
        TenantContextHolder.set(tenantWithOrderView());
        List<Long> tooMany = new ArrayList<>();
        for (long id = 1; id <= PaymentQueryApplicationService.MAX_ORDER_IDS + 1; id++) {
            tooMany.add(id);
        }

        ApiException error = assertThrows(ApiException.class, () -> controller.orderCollections(tooMany));

        assertEquals(400, error.getStatus());
        assertEquals("ORDER_IDS_TOO_MANY", error.getCode());
        verify(service, never()).orderCollections(any(), anyList());
    }

    @Test
    void delegatesWithContextTenant() {
        TenantContextHolder.set(tenantWithOrderView());
        when(service.orderCollections(TENANT_ID, List.of(69L, 70L))).thenReturn(List.of());

        List<OrderCollectionsDto> result = controller.orderCollections(List.of(69L, 70L));

        assertEquals(List.of(), result);
        verify(service).orderCollections(TENANT_ID, List.of(69L, 70L));
    }

    private static TenantContext tenantWithOrderView() {
        return new TenantContext(TENANT_ID, 1L, 100L, 5L, 1, List.of("order.view"));
    }
}

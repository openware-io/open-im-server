package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import io.openware.platform.order.application.VoidRuleApplicationService;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.domain.order.repository.MemberLookupProvider;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.model.RefundStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderVoidRuleGuardTest {
    private final OrderCancellationApplicationService cancellation = mock(OrderCancellationApplicationService.class);
    private final VoidRuleApplicationService rules = mock(VoidRuleApplicationService.class);
    private final OrderController controller = OrderControllerTestFactory.create(
            mock(OrderRepository.class), mock(KtvSessionApplicationService.class),
            mock(KtvServerSessionApplicationService.class), mock(ResourceStateClient.class),
            AuditClient.disabled(), cancellation, mock(DailySerialNumberGenerator.class),
            mock(MemberLookupProvider.class), rules);

    @BeforeEach
    void setUp() {
        TenantContextHolder.set(new TenantContext(100L, 10L, 7L, 99L, 1, List.of("order.void")));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void directVoidIsRejectedWhenRuleRequiresApproval() {
        // 订单夹具：领域模型不可变，用规范构造器一次成型（审计列不在模型上）。
        Order order = new Order(42L, 100L, null, 7L, "ORD-20260918-0001", null, "KTV", null, "DRAFT", RefundStatus.NONE.name(), "CNY",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, 0);
        when(cancellation.requireOrder(42L)).thenReturn(order);
        when(rules.isApprovalRequired(100L, "KTV", 7L)).thenReturn(true);

        ApiException failure = assertThrows(ApiException.class,
                () -> controller.voidOrder(42L, new OrderController.VoidRequest("reason")));

        assertEquals(409, failure.getStatus());
        assertEquals("VOID_APPROVAL_REQUIRED", failure.getCode());
        verify(cancellation, never()).voidOrder(42L, "reason");
    }
}

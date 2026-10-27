package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.dto.OrderItemView;
import io.openware.platform.order.application.InventoryApplicationService;
import io.openware.platform.order.application.InventoryRecoveryApplicationService;
import io.openware.platform.order.domain.order.repository.OrderItemRepository;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.model.OrderItem;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.model.RefundStatus;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 后台作废订单库存回补决定（{@code /admin/orders/{id}/items/{itemId}/inventory-recovery}）的成功与失败留痕。 */
class InventoryRecoveryAdminControllerAuditTest {

    private static final Long ORDER_ID = 88L;
    private static final Long ITEM_ID = 501L;
    private static final Long MATERIAL_ID = 300L;

    private OrderRepository orderMapper;
    private OrderItemRepository orderItemMapper;
    private InventoryApplicationService inventoryService;
    private AuditClient auditClient;
    private InventoryRecoveryAdminController controller;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderRepository.class);
        orderItemMapper = mock(OrderItemRepository.class);
        inventoryService = mock(InventoryApplicationService.class);
        auditClient = mock(AuditClient.class);
        controller = new InventoryRecoveryAdminController(new InventoryRecoveryApplicationService(
                orderMapper, orderItemMapper, inventoryService, auditClient));
        TenantContextHolder.set(new TenantContext(1001L, 1L, 2001L, 42L, 1,
                List.of("inventory.recovery.confirm")));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void decide_writesSucceededAudit() {
        when(orderMapper.findById(ORDER_ID)).thenReturn(Optional.of(order("VOIDED")));
        when(orderItemMapper.findById(ITEM_ID)).thenReturn(Optional.of(item("CONSUMED")));

        OrderItemView decided = controller.decide(ORDER_ID, ITEM_ID,
                new InventoryRecoveryAdminController.RecoveryRequest(true, "已退货入库"));

        assertEquals("REVERSED", decided.inventoryStatus());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.recovery.confirm", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertNull(record.errorCode());
    }

    @Test
    void decide_writesFailureAuditWithStableErrorCode() {
        when(orderMapper.findById(ORDER_ID)).thenReturn(Optional.of(order("DRAFT")));

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.decide(ORDER_ID, ITEM_ID,
                new InventoryRecoveryAdminController.RecoveryRequest(true, "已退货入库")));

        assertEquals("ORDER_STATUS_INVALID", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.recovery.confirm", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("ORDER_STATUS_INVALID", record.errorCode());
        assertEquals(String.valueOf(ITEM_ID), record.resourceId());
        // 失败留痕不带幂等键：重复失败必须各自留痕，也不覆盖成功路径的稳定键。
        assertNull(record.idempotencyKey());
    }

    @Test
    void decide_writesFailureAuditWhenItemAlreadyHandled() {
        when(orderMapper.findById(ORDER_ID)).thenReturn(Optional.of(order("VOIDED")));
        when(orderItemMapper.findById(ITEM_ID)).thenReturn(Optional.of(item("REVERSED")));

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.decide(ORDER_ID, ITEM_ID,
                new InventoryRecoveryAdminController.RecoveryRequest(false, "不回了")));

        assertEquals("INVENTORY_RECOVERY_INVALID", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.recovery.confirm", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("INVENTORY_RECOVERY_INVALID", record.errorCode());
    }

    /** 订单夹具：领域模型不可变，用规范构造器一次成型（审计列不在模型上）。 */
    private static Order order(String status) {
        return new Order(ORDER_ID, 1001L, null, 2001L, "ORD-20260918-0001", null, "KTV", null, status, RefundStatus.NONE.name(), "CNY",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, null, null, 0);
    }

    private static OrderItem item(String inventoryStatus) {
        return new OrderItem(ITEM_ID, 1001L, 2001L, "KTV", ORDER_ID, "ADD_ON", null, null, "纸巾",
                new BigDecimal("2"), new BigDecimal("2"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("4"), "CNY", null, OrderItem.STATUS_ACTIVE, OrderItem.SOURCE_MERCHANT,
                inventoryStatus, MATERIAL_ID, null, null, null, null);
    }

    private AuditClient.AuditRecord captured() {
        ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(captor.capture());
        return captor.getValue();
    }
}

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
import io.openware.platform.order.application.InventoryApplicationService;
import io.openware.platform.order.infra.persistence.mapper.OrderItemMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.po.OrderItemPo;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import java.math.BigDecimal;
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

    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private InventoryApplicationService inventoryService;
    private AuditClient auditClient;
    private InventoryRecoveryAdminController controller;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        inventoryService = mock(InventoryApplicationService.class);
        auditClient = mock(AuditClient.class);
        controller = new InventoryRecoveryAdminController(orderMapper, orderItemMapper, inventoryService, auditClient);
        TenantContextHolder.set(new TenantContext(1001L, 1L, 2001L, 42L, 1,
                List.of("inventory.recovery.confirm")));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void decide_writesSucceededAudit() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("VOIDED"));
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(item("CONSUMED"));

        OrderItemPo decided = controller.decide(ORDER_ID, ITEM_ID,
                new InventoryRecoveryAdminController.RecoveryRequest(true, "已退货入库"));

        assertEquals("REVERSED", decided.getInventoryStatus());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.recovery.confirm", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertNull(record.errorCode());
    }

    @Test
    void decide_writesFailureAuditWithStableErrorCode() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("SERVING"));

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
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("VOIDED"));
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(item("REVERSED"));

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.decide(ORDER_ID, ITEM_ID,
                new InventoryRecoveryAdminController.RecoveryRequest(false, "不回了")));

        assertEquals("INVENTORY_RECOVERY_INVALID", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.recovery.confirm", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("INVENTORY_RECOVERY_INVALID", record.errorCode());
    }

    private static OrderPo order(String status) {
        OrderPo po = new OrderPo();
        po.setId(ORDER_ID);
        po.setTenantId(1001L);
        po.setStoreId(2001L);
        po.setStatus(status);
        return po;
    }

    private static OrderItemPo item(String inventoryStatus) {
        OrderItemPo po = new OrderItemPo();
        po.setId(ITEM_ID);
        po.setTenantId(1001L);
        po.setOrderId(ORDER_ID);
        po.setInventoryMaterialId(MATERIAL_ID);
        po.setInventoryStatus(inventoryStatus);
        po.setQuantity(new BigDecimal("2"));
        po.setNameSnapshot("纸巾");
        return po;
    }

    private AuditClient.AuditRecord captured() {
        ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(captor.capture());
        return captor.getValue();
    }
}

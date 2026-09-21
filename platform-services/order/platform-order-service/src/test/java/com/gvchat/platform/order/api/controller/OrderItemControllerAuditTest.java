package com.gvchat.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.application.InventoryApplicationService;
import com.gvchat.platform.order.application.OrderAmountApplicationService;
import com.gvchat.platform.order.application.SettlementApplicationService;
import com.gvchat.platform.order.infra.persistence.mapper.CatalogItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderMapper;
import com.gvchat.platform.order.infra.persistence.mapper.ProductMapper;
import com.gvchat.platform.order.infra.persistence.po.CatalogItemPo;
import com.gvchat.platform.order.infra.persistence.po.OrderItemPo;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@code /business/orders/{id}/items**} 加项/确认/驳回的成功与失败留痕。
 * 这三个动作都会改写订单应收金额，此前只有加项成功路径有审计，失败与确认/驳回完全没有。
 */
class OrderItemControllerAuditTest {

    private static final Long ORDER_ID = 88L;
    private static final Long ITEM_ID = 501L;
    private static final Long CATALOG_ITEM_ID = 700L;
    private static final long TENANT_ID = 1001L;
    private static final Long STORE_ID = 2001L;

    private OrderItemMapper orderItemMapper;
    private CatalogItemMapper catalogItemMapper;
    private OrderMapper orderMapper;
    private ProductMapper productMapper;
    private AuditClient auditClient;
    private OrderItemController controller;

    @BeforeEach
    void setUp() {
        orderItemMapper = mock(OrderItemMapper.class);
        catalogItemMapper = mock(CatalogItemMapper.class);
        orderMapper = mock(OrderMapper.class);
        productMapper = mock(ProductMapper.class);
        auditClient = mock(AuditClient.class);
        controller = new OrderItemController(orderItemMapper, catalogItemMapper,
                mock(SettlementApplicationService.class), orderMapper,
                mock(InventoryApplicationService.class), productMapper,
                mock(OrderAmountApplicationService.class), auditClient);
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 42L, 1,
                List.of("order.add_item")));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void addItem_writesSucceededAudit() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("DRAFT"));
        when(catalogItemMapper.selectById(CATALOG_ITEM_ID)).thenReturn(catalogItem());

        OrderItemPo item = controller.addItem(ORDER_ID, new OrderItemController.AddItemRequest(
                TENANT_ID, "ADD_ON", CATALOG_ITEM_ID, null, null, null, BigDecimal.ONE, "MERCHANT"));

        assertEquals("ACTIVE", item.getStatus());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.item.add", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertNull(record.errorCode());
    }

    @Test
    void addItem_writesFailureAuditWhenOrderMissing() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);
        when(orderMapper.selectTenantIdById(ORDER_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.addItem(ORDER_ID, new OrderItemController.AddItemRequest(
                        TENANT_ID, "ADD_ON", CATALOG_ITEM_ID, null, null, null, BigDecimal.ONE, "MERCHANT")));

        assertEquals("ORDER_NOT_FOUND", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.item.add", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("ORDER_NOT_FOUND", record.errorCode());
        assertNull(record.idempotencyKey());
        // 失败详情不含名称/单价等业务内容，只留定位 ID。
        assertEquals(String.valueOf(ORDER_ID), record.resourceId());
    }

    /** 跨租户访问：403 TENANT_SCOPE_DENIED 同样留失败痕迹。 */
    @Test
    void addItem_writesFailureAuditOnCrossTenantAccess() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);
        when(orderMapper.selectTenantIdById(ORDER_ID)).thenReturn(999L);

        ApiException ex = assertThrows(ApiException.class,
                () -> controller.addItem(ORDER_ID, new OrderItemController.AddItemRequest(
                        TENANT_ID, "ADD_ON", CATALOG_ITEM_ID, null, null, null, BigDecimal.ONE, "MERCHANT")));

        assertEquals(403, ex.getStatus());
        assertEquals("TENANT_SCOPE_DENIED", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.item.add", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("TENANT_SCOPE_DENIED", record.errorCode());
    }

    @Test
    void confirm_writesSucceededAudit() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("DRAFT"));
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(item("PENDING_APPROVAL"));
        when(orderItemMapper.markApproval(any(), any(), any(), any(), any())).thenReturn(1);
        when(orderItemMapper.updateById(any(OrderItemPo.class))).thenReturn(1);

        OrderItemPo confirmed = controller.confirm(ORDER_ID, ITEM_ID);

        assertEquals("ACTIVE", confirmed.getStatus());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.item.confirm", record.action());
        assertEquals("订单加项确认", record.actionLabel());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertEquals("ord_order_item", record.resourceType());
        assertEquals(String.valueOf(ITEM_ID), record.resourceId());
    }

    /** 已经是 ACTIVE 的重复确认：幂等返回该明细，不再重复留痕（并发/重复点击的收口）。 */
    @Test
    void confirm_isIdempotentWhenAlreadyActive() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("DRAFT"));
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(item("ACTIVE"));

        OrderItemPo confirmed = controller.confirm(ORDER_ID, ITEM_ID);

        assertEquals("ACTIVE", confirmed.getStatus());
        verify(orderItemMapper, never()).markApproval(any(), any(), any(), any(), any());
        verify(auditClient, never()).recordAsync(any());
    }

    /** 状态不符（已拒绝）：失败留痕 + 稳定错误码（原「已生效」场景改为幂等，这里用 REJECTED 守住失败路径）。 */
    @Test
    void confirm_writesFailureAuditWithStableErrorCode() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("DRAFT"));
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(item("REJECTED"));

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.confirm(ORDER_ID, ITEM_ID));

        assertEquals("ORDER_ITEM_STATUS_INVALID", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.item.confirm", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("ORDER_ITEM_STATUS_INVALID", record.errorCode());
        assertNull(record.idempotencyKey());
    }

    /** 并发输家：条件更新影响 0 行（别人已处理）→ 409，且不重复扣库存/不留成功痕迹。 */
    @Test
    void confirm_writesFailureAuditWhenConcurrentlyHandled() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("DRAFT"));
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(item("PENDING_APPROVAL"));
        when(orderItemMapper.markApproval(any(), any(), any(), any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.confirm(ORDER_ID, ITEM_ID));

        assertEquals("ORDER_ITEM_STATUS_INVALID", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.item.confirm", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    }

    @Test
    void reject_writesSucceededAudit() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("DRAFT"));
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(item("PENDING_APPROVAL"));
        when(orderItemMapper.markApproval(any(), any(), any(), any(), any())).thenReturn(1);

        OrderItemPo rejected = controller.reject(ORDER_ID, ITEM_ID);

        assertEquals("REJECTED", rejected.getStatus());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.item.reject", record.action());
        assertEquals("订单加项驳回", record.actionLabel());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    }

    /** 已经是 REJECTED 的重复拒绝：幂等返回，不重复留痕。 */
    @Test
    void reject_isIdempotentWhenAlreadyRejected() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("DRAFT"));
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(item("REJECTED"));

        assertEquals("REJECTED", controller.reject(ORDER_ID, ITEM_ID).getStatus());
        verify(orderItemMapper, never()).markApproval(any(), any(), any(), any(), any());
        verify(auditClient, never()).recordAsync(any());
    }

    @Test
    void reject_writesFailureAuditWithStableErrorCode() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("DRAFT"));
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(item("ACTIVE"));

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.reject(ORDER_ID, ITEM_ID));

        assertEquals("ORDER_ITEM_STATUS_INVALID", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.item.reject", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("ORDER_ITEM_STATUS_INVALID", record.errorCode());
    }

    private static OrderPo order(String status) {
        OrderPo po = new OrderPo();
        po.setId(ORDER_ID);
        po.setTenantId(TENANT_ID);
        po.setStoreId(STORE_ID);
        po.setStatus(status);
        po.setCurrencyCode("CNY");
        return po;
    }

    private static CatalogItemPo catalogItem() {
        CatalogItemPo po = new CatalogItemPo();
        po.setId(CATALOG_ITEM_ID);
        po.setTenantId(TENANT_ID);
        po.setStoreId(STORE_ID);
        po.setName("可乐");
        po.setItemType("PRODUCT");
        po.setUnitPrice(new BigDecimal("500"));
        po.setStatus("ACTIVE");
        po.setStockControlled(false);
        return po;
    }

    private static OrderItemPo item(String status) {
        OrderItemPo po = new OrderItemPo();
        po.setId(ITEM_ID);
        po.setOrderId(ORDER_ID);
        po.setNameSnapshot("可乐");
        po.setStatus(status);
        po.setInventoryStatus("NOT_APPLICABLE");
        return po;
    }

    private AuditClient.AuditRecord captured() {
        ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(captor.capture());
        return captor.getValue();
    }
}

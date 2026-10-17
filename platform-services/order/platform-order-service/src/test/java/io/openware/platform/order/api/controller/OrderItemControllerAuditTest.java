package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.platform.order.api.dto.OrderItemView;
import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.InventoryApplicationService;
import io.openware.platform.order.application.OrderAmountApplicationService;
import io.openware.platform.order.application.OrderItemApplicationService;
import io.openware.platform.order.application.SettlementApplicationService;
import io.openware.platform.order.domain.catalog.model.CatalogItem;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.domain.order.repository.OrderItemRepository;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.model.OrderItem;
import io.openware.platform.order.domain.order.model.Order;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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

    private OrderItemRepository orderItemMapper;
    private CatalogItemRepository catalogItemRepository;
    private OrderRepository orderMapper;
    private ProductRepository productRepository;
    private AuditClient auditClient;
    private OrderItemController controller;

    @BeforeEach
    void setUp() {
        orderItemMapper = mock(OrderItemRepository.class);
        catalogItemRepository = mock(CatalogItemRepository.class);
        orderMapper = mock(OrderRepository.class);
        productRepository = mock(ProductRepository.class);
        auditClient = mock(AuditClient.class);
        controller = new OrderItemController(new OrderItemApplicationService(orderItemMapper,
                catalogItemRepository, orderMapper, mock(InventoryApplicationService.class), productRepository,
                mock(OrderAmountApplicationService.class), auditClient, null, null),
                mock(SettlementApplicationService.class));
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 42L, 1,
                List.of("order.add_item")));
        // 端口约定 insert 返回「带主键的聚合」：mock 必须回填主键，控制器要用返回值继续（库存/审计/响应）。
        when(orderItemMapper.insert(any(OrderItem.class))).thenAnswer(invocation -> {
            OrderItem inserted = invocation.getArgument(0);
            return inserted.withId(ITEM_ID);
        });
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void addItem_writesSucceededAudit() {
        when(orderMapper.findById(ORDER_ID)).thenReturn(Optional.of(order("DRAFT")));
        when(catalogItemRepository.findById(CATALOG_ITEM_ID)).thenReturn(Optional.of(catalogItem()));

        OrderItemView item = controller.addItem(ORDER_ID, new OrderItemController.AddItemRequest(
                TENANT_ID, "ADD_ON", CATALOG_ITEM_ID, null, null, null, BigDecimal.ONE, "MERCHANT"));

        assertEquals("ACTIVE", item.status());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.item.add", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertNull(record.errorCode());
    }

    @Test
    void addItem_writesFailureAuditWhenOrderMissing() {
        when(orderMapper.findById(ORDER_ID)).thenReturn(Optional.empty());
        when(orderMapper.findTenantIdById(ORDER_ID)).thenReturn(Optional.empty());

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
        when(orderMapper.findById(ORDER_ID)).thenReturn(Optional.empty());
        when(orderMapper.findTenantIdById(ORDER_ID)).thenReturn(Optional.of(999L));

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
        when(orderMapper.findById(ORDER_ID)).thenReturn(Optional.of(order("DRAFT")));
        when(orderItemMapper.findById(ITEM_ID)).thenReturn(Optional.of(item("PENDING_APPROVAL")));
        when(orderItemMapper.markApproval(any(), any(), any(), any(), any())).thenReturn(true);

        OrderItemView confirmed = controller.confirm(ORDER_ID, ITEM_ID);

        assertEquals("ACTIVE", confirmed.status());
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
        when(orderMapper.findById(ORDER_ID)).thenReturn(Optional.of(order("DRAFT")));
        when(orderItemMapper.findById(ITEM_ID)).thenReturn(Optional.of(item("ACTIVE")));

        OrderItemView confirmed = controller.confirm(ORDER_ID, ITEM_ID);

        assertEquals("ACTIVE", confirmed.status());
        verify(orderItemMapper, never()).markApproval(any(), any(), any(), any(), any());
        verify(auditClient, never()).recordAsync(any());
    }

    /** 状态不符（已拒绝）：失败留痕 + 稳定错误码（原「已生效」场景改为幂等，这里用 REJECTED 守住失败路径）。 */
    @Test
    void confirm_writesFailureAuditWithStableErrorCode() {
        when(orderMapper.findById(ORDER_ID)).thenReturn(Optional.of(order("DRAFT")));
        when(orderItemMapper.findById(ITEM_ID)).thenReturn(Optional.of(item("REJECTED")));

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
        when(orderMapper.findById(ORDER_ID)).thenReturn(Optional.of(order("DRAFT")));
        when(orderItemMapper.findById(ITEM_ID)).thenReturn(Optional.of(item("PENDING_APPROVAL")));
        when(orderItemMapper.markApproval(any(), any(), any(), any(), any())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.confirm(ORDER_ID, ITEM_ID));

        assertEquals("ORDER_ITEM_STATUS_INVALID", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.item.confirm", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    }

    @Test
    void reject_writesSucceededAudit() {
        when(orderMapper.findById(ORDER_ID)).thenReturn(Optional.of(order("DRAFT")));
        when(orderItemMapper.findById(ITEM_ID)).thenReturn(Optional.of(item("PENDING_APPROVAL")));
        when(orderItemMapper.markApproval(any(), any(), any(), any(), any())).thenReturn(true);

        OrderItemView rejected = controller.reject(ORDER_ID, ITEM_ID);

        assertEquals("REJECTED", rejected.status());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.item.reject", record.action());
        assertEquals("订单加项驳回", record.actionLabel());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    }

    /** 已经是 REJECTED 的重复拒绝：幂等返回，不重复留痕。 */
    @Test
    void reject_isIdempotentWhenAlreadyRejected() {
        when(orderMapper.findById(ORDER_ID)).thenReturn(Optional.of(order("DRAFT")));
        when(orderItemMapper.findById(ITEM_ID)).thenReturn(Optional.of(item("REJECTED")));

        assertEquals("REJECTED", controller.reject(ORDER_ID, ITEM_ID).status());
        verify(orderItemMapper, never()).markApproval(any(), any(), any(), any(), any());
        verify(auditClient, never()).recordAsync(any());
    }

    @Test
    void reject_writesFailureAuditWithStableErrorCode() {
        when(orderMapper.findById(ORDER_ID)).thenReturn(Optional.of(order("DRAFT")));
        when(orderItemMapper.findById(ITEM_ID)).thenReturn(Optional.of(item("ACTIVE")));

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.reject(ORDER_ID, ITEM_ID));

        assertEquals("ORDER_ITEM_STATUS_INVALID", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.item.reject", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("ORDER_ITEM_STATUS_INVALID", record.errorCode());
    }

    /** 订单夹具：领域模型不可变，用规范构造器一次成型（审计列不在模型上）。 */
    private static Order order(String status) {
        return new Order(ORDER_ID, TENANT_ID, null, STORE_ID, "ORD-20260918-0001", null, "KTV", null, status,
                "CNY", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, null, null, 0);
    }

    private static CatalogItem catalogItem() {
        return new CatalogItem(CATALOG_ITEM_ID, null, TENANT_ID, STORE_ID, "酒水", "PRODUCT", "可乐", "瓶",
                new BigDecimal("500"), null, List.of(), null, "ACTIVE", false, 0);
    }

    private static OrderItem item(String status) {
        return new OrderItem(ITEM_ID, TENANT_ID, STORE_ID, "KTV", ORDER_ID, "ADD_ON", null, null, "可乐",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, "CNY",
                null, status, OrderItem.SOURCE_MERCHANT, "NOT_APPLICABLE", null, null, null, null, null);
    }

    private AuditClient.AuditRecord captured() {
        ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(captor.capture());
        return captor.getValue();
    }
}

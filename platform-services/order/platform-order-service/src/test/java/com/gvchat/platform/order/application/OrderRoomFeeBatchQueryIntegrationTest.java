package com.gvchat.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.MqProducer;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.infra.client.PaymentCollectedClient;
import com.gvchat.platform.order.infra.client.ResourceStateClient;
import com.gvchat.platform.order.infra.mq.EventOutboxRelay;
import com.gvchat.platform.order.infra.persistence.mapper.OrderItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderMapper;
import com.gvchat.platform.order.infra.persistence.po.OrderItemPo;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 订单投影「实时合计」所用的**批量房费明细查询**真跑回归（H2 MODE=MySQL + 真实 MyBatis + 租户拦截器）。
 *
 * <p>为什么单独要一条集成用例：控制器单测里 {@code roomFeeItemAmountsByOrderIds} 是 mock，
 * 只有真库才验证得了三件事——
 * <ol>
 *   <li>一次调用能带出**多个订单**各自的房费金额（整页订单一条 SQL，不是 N+1）；</li>
 *   <li>只认 status=ACTIVE 的 ROOM_FEE 行（与账单/订单合计重算同一口径），非房费明细不计入；</li>
 *   <li>没有任何房费明细的订单（甚至整单没有明细）不会炸、也不会凭空出现在结果里。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderRoomFeeBatchQueryIntegrationTest {

    private static final long TENANT_ID = 1001L;
    private static final long STORE_ID = 2001L;

    @MockitoBean
    private MqProducer mqProducer;

    @MockitoBean
    private MqConsumerFactory mqConsumerFactory;

    @MockitoBean
    private AuditClient auditClient;

    @MockitoBean
    private EventOutboxRelay eventOutboxRelay;

    @MockitoBean
    private ResourceStateClient resourceStateClient;

    @MockitoBean
    private PaymentCollectedClient paymentCollectedClient;

    @Autowired
    private KtvSessionApplicationService ktvSessionApplicationService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @BeforeEach
    void setUpTenant() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 0L, 0));
    }

    @AfterEach
    void tearDownTenant() {
        TenantContextHolder.clear();
    }

    /** 一次调用取回多个订单的房费金额；无房费明细（甚至整单无明细）的订单缺席而不报错。 */
    @Test
    void batchLoadsRoomFeeAmountsForWholePageAndSkipsOrdersWithoutRoomFeeItems() {
        OrderPo withRoomFee = insertOrder();
        OrderPo anotherWithRoomFee = insertOrder();
        OrderPo onlyProductItems = insertOrder();
        OrderPo withoutAnyItem = insertOrder();

        insertRoomFeeItem(withRoomFee.getId(), "4000", "ACTIVE");
        insertRoomFeeItem(anotherWithRoomFee.getId(), "1500", "ACTIVE");
        // 非房费明细：不得混进房费合计（否则实时合计会把商品也算成房费而被差额法扣掉）。
        insertItem(onlyProductItems.getId(), "PRODUCT", "2150", "ACTIVE");

        Map<Long, Long> amounts = ktvSessionApplicationService.roomFeeItemAmountsByOrderIds(
                List.of(withRoomFee.getId(), anotherWithRoomFee.getId(),
                        onlyProductItems.getId(), withoutAnyItem.getId()));

        assertEquals(4000L, amounts.get(withRoomFee.getId()), "订单 A 的房费明细金额");
        assertEquals(1500L, amounts.get(anotherWithRoomFee.getId()), "订单 B 与 A 在同一次批量查询里取回");
        assertFalse(amounts.containsKey(onlyProductItems.getId()), "只有商品明细的订单没有房费金额");
        assertFalse(amounts.containsKey(withoutAnyItem.getId()), "整单没有明细也不能出现在结果里");
        assertEquals(2, amounts.size(), "结果只应包含真正有 ACTIVE 房费明细的订单");
    }

    /** 行口径与账单一致：只统计 ACTIVE 的 ROOM_FEE 行，历史脏数据多行时一并求和。 */
    @Test
    void batchSumsActiveRoomFeeRowsOnly() {
        OrderPo order = insertOrder();
        insertRoomFeeItem(order.getId(), "4000", "ACTIVE");
        // 历史脏数据：同一订单第二行 ACTIVE 房费（账单/订单重算都是全行求和，这里必须一致）。
        insertRoomFeeItem(order.getId(), "1000", "ACTIVE");
        // 被拒绝/待确认的房费明细不计入（它们也不进订单合计）。
        insertRoomFeeItem(order.getId(), "9999", "REJECTED");
        insertRoomFeeItem(order.getId(), "8888", "PENDING_APPROVAL");

        Map<Long, Long> amounts =
                ktvSessionApplicationService.roomFeeItemAmountsByOrderIds(List.of(order.getId()));

        assertEquals(5000L, amounts.get(order.getId()), "ACTIVE 房费行求和；非 ACTIVE 行不得计入");
    }

    /** 空订单 id 集合不走 SQL（空 IN () 是非法语句），直接给空结果。 */
    @Test
    void emptyOrderIdsShortCircuitsWithoutQuery() {
        assertTrue(ktvSessionApplicationService.roomFeeItemAmountsByOrderIds(List.of()).isEmpty());
        assertTrue(ktvSessionApplicationService.roomFeeItemAmountsByOrderIds(null).isEmpty());
        assertTrue(ktvSessionApplicationService.orderAmountSumsByOrderIds(List.of()).isEmpty());
        assertTrue(ktvSessionApplicationService.orderAmountSumsByOrderIds(null).isEmpty());
    }

    /**
     * 订单投影的「实时合计」要用两个数：全部 ACTIVE 明细合计（库内合计为 0 时的回退基数）与
     * 其中 ROOM_FEE 明细合计（差额法要扣掉的库内房费快照）。一次批量查询同时给出，行口径与账单一致
     * （历史脏数据多行房费一并求和；非 ACTIVE 行不计入）。
     */
    @Test
    void batchLoadsActiveItemSumsWithRoomFeeBreakdown() {
        OrderPo order = insertOrder();
        insertRoomFeeItem(order.getId(), "4000", "ACTIVE");
        insertRoomFeeItem(order.getId(), "1000", "ACTIVE");
        insertItem(order.getId(), "PRODUCT", "2150", "ACTIVE");
        insertItem(order.getId(), "PRODUCT", "999", "REJECTED");
        OrderPo withoutAnyItem = insertOrder();

        Map<Long, KtvSessionApplicationService.OrderAmountSums> sums =
                ktvSessionApplicationService.orderAmountSumsByOrderIds(
                        List.of(order.getId(), withoutAnyItem.getId()));

        assertEquals(7150L, sums.get(order.getId()).activeItemsMinor(),
                "全部 ACTIVE 明细合计（4000 + 1000 + 2150）");
        assertEquals(5000L, sums.get(order.getId()).roomFeeItemsMinor(), "其中房费明细合计（多行一并求和）");
        assertFalse(sums.containsKey(withoutAnyItem.getId()), "整单没有明细不出现在结果里");
    }

    private OrderPo insertOrder() {
        OrderPo po = new OrderPo();
        po.setTenantId(TENANT_ID);
        po.setOrganizationId(1L);
        po.setStoreId(STORE_ID);
        po.setOrderNo("ORD-BATCH-" + System.nanoTime());
        po.setBusinessType("KTV");
        po.setStatus("SERVING");
        po.setCurrencyCode("CNY");
        po.setSubtotalAmount(BigDecimal.ZERO);
        po.setDiscountAmount(BigDecimal.ZERO);
        po.setTaxAmount(BigDecimal.ZERO);
        po.setTotalAmount(BigDecimal.ZERO);
        po.setPaidAmount(BigDecimal.ZERO);
        po.setRefundableAmount(BigDecimal.ZERO);
        po.setVersion(0);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        orderMapper.insert(po);
        return po;
    }

    private void insertRoomFeeItem(Long orderId, String totalAmount, String status) {
        insertItem(orderId, "ROOM_FEE", totalAmount, status);
    }

    /** 金额列存的就是最小货币单位整数（与 {@code BillApplicationService#toMinor} 注释同口径）。 */
    private void insertItem(Long orderId, String itemType, String totalAmount, String status) {
        OrderItemPo item = new OrderItemPo();
        item.setTenantId(TENANT_ID);
        item.setOrderId(orderId);
        item.setItemType(itemType);
        item.setNameSnapshot("ROOM_FEE".equals(itemType) ? "包厢费（含 1 名服务人员）" : "可乐");
        item.setUnitPrice(new BigDecimal(totalAmount));
        item.setQuantity(BigDecimal.ONE);
        item.setDiscountAmount(BigDecimal.ZERO);
        item.setTaxAmount(BigDecimal.ZERO);
        item.setTotalAmount(new BigDecimal(totalAmount));
        item.setCurrencyCode("CNY");
        item.setStatus(status);
        item.setSource("MERCHANT");
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        orderItemMapper.insert(item);
    }
}

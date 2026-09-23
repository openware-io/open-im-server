package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.LambdaUtils;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.DailySerialNumberGenerator;
import io.openware.platform.order.application.KtvServerSessionApplicationService;
import io.openware.platform.order.application.KtvSessionApplicationService;
import io.openware.platform.order.application.OrderCancellationApplicationService;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.infra.persistence.mapper.CustomerLookupMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.po.KtvSessionPo;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 订单投影的「实时应付合计」（{@code OrderPo#liveTotalAmount}）回归。
 *
 * <p>背景（ACK dev 实测，订单 73 开台中）：SaaS 收银台房卡把 {@code totalAmount + roomEstimatedFee}
 * 相加，而 {@code totalAmount} 里**已经含**刷新任务（{@code KtvRoomFeeRefreshJob} →
 * {@code upsertRoomFeeItem} + {@code OrderAmountApplicationService#recalculate}）写进去的 ROOM_FEE 明细，
 * 再加一次实时估算就把包厢费算了两遍。
 *
 * <p>修法：服务端按账单同一「差额法」（{@code BillApplicationService#buildBill}：
 * {@code total = 合计基数 − ROOM_FEE 明细合计 + 实时房费}；合计基数与差额法都实现在
 * {@code OrderAmountApplicationService}）把可直接展示的合计算好放进 {@code liveTotalAmount}，
 * 客户端不再做任何加减；{@code totalAmount} 保持库内原值不动。
 *
 * <p>整页订单只用**一次**批量查询拿齐「已生效明细合计 + 其中房费明细合计」
 * （{@code orderAmountSumsByOrderIds}）：差额法要扣房费快照，合计基数在库内合计为 0 时要用全明细合计。
 */
class OrderLiveTotalProjectionTest {

    private static final long TENANT_ID = 100L;
    private static final long ACCOUNT_ID = 7L;
    private static final long STORE_ID = 100L;
    /** 线上那张单：加项 2150 + 房费快照 4000。 */
    private static final long ORDER_ID = 73L;
    private static final BigDecimal STORED_TOTAL = new BigDecimal("6150");
    private static final long STALE_ROOM_FEE_ITEM = 4000L;
    private static final long LIVE_ROOM_FEE = 4000L;

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final KtvSessionApplicationService ktvSessionService = mock(KtvSessionApplicationService.class);
    private final OrderController controller = new OrderController(
            orderMapper,
            ktvSessionService,
            mock(KtvServerSessionApplicationService.class),
            mock(ResourceStateClient.class),
            AuditClient.disabled(),
            mock(OrderCancellationApplicationService.class),
            mock(DailySerialNumberGenerator.class),
            mock(CustomerLookupMapper.class));

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /** 列表/详情都会用 LambdaQueryWrapper 取单，单元测试没有 MyBatis 启动过程，必须手工装 TableInfo。 */
    @BeforeEach
    void initTableInfo() {
        TableInfo tableInfo = TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderPo.class);
        LambdaUtils.installCache(tableInfo);
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, STORE_ID, ACCOUNT_ID, 1, List.of("order.view")));
    }

    /**
     * (a) 开台中 + 明细里是「上次刷新」的房费快照：
     * {@code liveTotalAmount = totalAmount − 明细 + 实时估算}，**不是** {@code totalAmount + 实时估算}。
     * 用线上真实数字：6150 − 4000 + 4000 = 6150（前端算法会给 10150）。
     */
    @Test
    void openSessionExposesLiveTotalInsteadOfDoubleCountedRoomFee() {
        OrderPo order = order(ORDER_ID, STORED_TOTAL);
        KtvSessionPo session = session(ORDER_ID, "OPEN", LIVE_ROOM_FEE);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(ktvSessionService.listByOrderIds(List.of(ORDER_ID))).thenReturn(Map.of(ORDER_ID, session));
        when(ktvSessionService.orderAmountSumsByOrderIds(List.of(ORDER_ID)))
                .thenReturn(Map.of(ORDER_ID, sums(STORED_TOTAL.longValueExact(), STALE_ROOM_FEE_ITEM)));

        OrderPo detail = controller.detail(ORDER_ID);

        assertEquals(0, detail.getLiveTotalAmount().compareTo(new BigDecimal("6150")),
                "开台中合计必须是差额法结果 6150");
        assertNotEquals(0, detail.getLiveTotalAmount().compareTo(new BigDecimal("10150")),
                "绝不能是「库内合计 + 实时房费」的重复计数结果");
        // 库内原值不得被改写（它仍是账单合计基数的输入）。
        assertEquals(0, detail.getTotalAmount().compareTo(STORED_TOTAL));
        // 实时房费必须来自统一计费入口（fillLiveEstimate → liveRoomFee），不是本处另写的公式。
        verify(ktvSessionService).fillLiveEstimate(session, STORE_ID);
    }

    /**
     * (a') 快照与实时值不同时，差额法真的在「换」而不是「碰巧相等」：
     * 6150 − 2000 + 4000 = 8150（既不是 6150，也不是 10150）。
     */
    @Test
    void liveTotalReplacesStaleRoomFeeSnapshotWithLiveEstimate() {
        OrderPo order = order(ORDER_ID, STORED_TOTAL);
        KtvSessionPo session = session(ORDER_ID, "PAUSED", LIVE_ROOM_FEE);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(ktvSessionService.listByOrderIds(List.of(ORDER_ID))).thenReturn(Map.of(ORDER_ID, session));
        when(ktvSessionService.orderAmountSumsByOrderIds(List.of(ORDER_ID)))
                .thenReturn(Map.of(ORDER_ID, sums(STORED_TOTAL.longValueExact(), 2000L)));

        OrderPo detail = controller.detail(ORDER_ID);

        assertEquals(0, detail.getLiveTotalAmount().compareTo(new BigDecimal("8150")),
                "PAUSED（挂单）同样按差额法换成实时房费");
    }

    /** (b) 没有开台中的会话：库内合计已是终值，原样返回，不做任何加减。 */
    @Test
    void finishedSessionKeepsStoredTotal() {
        OrderPo order = order(ORDER_ID, STORED_TOTAL);
        KtvSessionPo closed = session(ORDER_ID, "CLOSED", null);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(ktvSessionService.listByOrderIds(List.of(ORDER_ID))).thenReturn(Map.of(ORDER_ID, closed));
        when(ktvSessionService.orderAmountSumsByOrderIds(List.of(ORDER_ID)))
                .thenReturn(Map.of(ORDER_ID, sums(STORED_TOTAL.longValueExact(), STALE_ROOM_FEE_ITEM)));

        OrderPo detail = controller.detail(ORDER_ID);

        assertEquals(0, detail.getLiveTotalAmount().compareTo(STORED_TOTAL),
                "已结台：明细快照就是最终值，liveTotalAmount == totalAmount");
    }

    /** (b') 连会话都没有（非 KTV 单/未选包厢）：同样等于库内合计，且不因缺会话而漏填。 */
    @Test
    void orderWithoutSessionKeepsStoredTotal() {
        OrderPo order = order(ORDER_ID, STORED_TOTAL);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(ktvSessionService.listByOrderIds(List.of(ORDER_ID))).thenReturn(Map.of());

        OrderPo detail = controller.detail(ORDER_ID);

        assertNotNull(detail.getLiveTotalAmount(), "没有会话的订单也必须给出可直接展示的合计");
        assertEquals(0, detail.getLiveTotalAmount().compareTo(STORED_TOTAL));
    }

    /**
     * (b'') 库内合计为 0（异常/存量数据，线上订单 38）但明细有金额：合计基数按「已生效明细之和」回退——
     * 与账单同一份实现（{@code OrderAmountApplicationService#totalBasisMinor}），
     * 看板不能显示 0 而账单显示 2150。
     */
    @Test
    void zeroStoredTotalFallsBackToActiveItemsBasis() {
        OrderPo order = order(ORDER_ID, BigDecimal.ZERO);
        KtvSessionPo closed = session(ORDER_ID, "CLOSED", null);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(ktvSessionService.listByOrderIds(List.of(ORDER_ID))).thenReturn(Map.of(ORDER_ID, closed));
        when(ktvSessionService.orderAmountSumsByOrderIds(List.of(ORDER_ID)))
                .thenReturn(Map.of(ORDER_ID, sums(2150L, 0L)));

        OrderPo detail = controller.detail(ORDER_ID);

        assertEquals(0, detail.getLiveTotalAmount().compareTo(new BigDecimal("2150")),
                "库内合计为 0 时按明细合计回退，与账单同一口径");
    }

    /**
     * (c) 整页订单只批量查一次明细汇总，且没有任何会话/明细的订单不炸：
     * 订单 A（开台中，有房费快照）+ 订单 B（无会话、无明细）一次 list 调用走通，
     * 明细汇总查询恰好被调用 1 次、入参是**两个**订单 id（不是逐单 N+1）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void listBatchLoadsOrderItemSumsForWholePageOnceAndToleratesMissingRows() {
        long orderA = 73L;
        long orderB = 74L;
        OrderPo a = order(orderA, STORED_TOTAL);
        OrderPo b = order(orderB, new BigDecimal("900"));
        when(orderMapper.selectList(any())).thenReturn(List.of(a, b));
        KtvSessionPo openSession = session(orderA, "OPEN", LIVE_ROOM_FEE);
        when(ktvSessionService.listByOrderIds(anyList())).thenReturn(Map.of(orderA, openSession));
        when(ktvSessionService.orderAmountSumsByOrderIds(anyList()))
                .thenReturn(Map.of(orderA, sums(STORED_TOTAL.longValueExact(), STALE_ROOM_FEE_ITEM)));

        List<OrderPo> orders = controller.list(null, null);

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(ktvSessionService, times(1)).orderAmountSumsByOrderIds(captor.capture());
        assertEquals(List.of(orderA, orderB), captor.getValue(),
                "整页订单必须一次批量查完（逐单查就是 N+1）");
        assertEquals(0, orders.get(0).getLiveTotalAmount().compareTo(new BigDecimal("6150")));
        assertEquals(0, orders.get(1).getLiveTotalAmount().compareTo(new BigDecimal("900")),
                "没有会话/明细的订单按库内合计返回，不抛错");
        verify(ktvSessionService, times(1)).listByOrderIds(List.of(orderA, orderB));
    }

    /** 非 KTV 单页（空列表）不该产生任何会话/明细查询：读路径不白查一次。 */
    @Test
    void emptyPageDoesNotQueryAnything() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        controller.list(null, null);

        verify(ktvSessionService, never()).listByOrderIds(anyList());
        verify(ktvSessionService, never()).orderAmountSumsByOrderIds(anyList());
    }

    /** 明细批量汇总：全部 ACTIVE 明细合计 + 其中房费明细合计（两个数一次查询拿齐）。 */
    private static KtvSessionApplicationService.OrderAmountSums sums(long activeItemsMinor, long roomFeeItemsMinor) {
        return new KtvSessionApplicationService.OrderAmountSums(activeItemsMinor, roomFeeItemsMinor);
    }

    private static OrderPo order(Long id, BigDecimal totalAmount) {
        OrderPo po = new OrderPo();
        po.setId(id);
        po.setTenantId(TENANT_ID);
        po.setStoreId(STORE_ID);
        po.setStatus("SERVING");
        po.setTotalAmount(totalAmount);
        return po;
    }

    /**
     * 会话 PO：{@code estimatedRoomFee} 是非持久化字段，生产里由
     * {@code KtvSessionApplicationService#fillLiveEstimate} 写入（mock 装配下不会真的执行，
     * 这里手动放进与线上一致的值，等价于「实时估算已算好」）。
     */
    private static KtvSessionPo session(Long orderId, String status, Long estimatedRoomFee) {
        KtvSessionPo po = new KtvSessionPo();
        po.setId(48L);
        po.setOrderId(orderId);
        po.setTenantId(TENANT_ID);
        po.setStatus(status);
        po.setRoomResourceId(1023L);
        po.setRoomNameSnapshot("豪华包 XL02");
        po.setEstimatedRoomFee(estimatedRoomFee);
        return po;
    }
}

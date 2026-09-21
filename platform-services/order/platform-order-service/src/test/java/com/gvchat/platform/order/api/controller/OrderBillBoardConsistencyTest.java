package com.gvchat.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.LambdaUtils;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.application.BillApplicationService;
import com.gvchat.platform.order.application.DailySerialNumberGenerator;
import com.gvchat.platform.order.application.KtvServerSessionApplicationService;
import com.gvchat.platform.order.application.KtvSessionApplicationService;
import com.gvchat.platform.order.application.OrderAmountApplicationService;
import com.gvchat.platform.order.application.OrderCancellationApplicationService;
import com.gvchat.platform.order.application.dto.BillResult;
import com.gvchat.platform.order.domain.ktv.model.KtvBillingUnit;
import com.gvchat.platform.order.domain.ktv.model.KtvPricingPlan;
import com.gvchat.platform.order.domain.ktv.model.KtvRoundingDirection;
import com.gvchat.platform.order.domain.ktv.port.KtvPricingPlanProvider;
import com.gvchat.platform.order.infra.client.ResourceStateClient;
import com.gvchat.platform.order.infra.persistence.mapper.CustomerLookupMapper;
import com.gvchat.platform.order.infra.persistence.mapper.KtvServerSessionMapper;
import com.gvchat.platform.order.infra.persistence.mapper.KtvSessionMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderMapper;
import com.gvchat.platform.order.infra.persistence.po.KtvServerSessionPo;
import com.gvchat.platform.order.infra.persistence.po.KtvSessionPo;
import com.gvchat.platform.order.infra.persistence.po.OrderItemPo;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 「收银台卡片 / 订单列表」与「账单」两个表面**逐状态同值**的回归（产品口径的唯一验收线）。
 *
 * <p>背景：收银台房卡算的是 {@code order.liveTotalAmount}（服务端差额法），账单算的是自己的
 * {@code totalMinor}。两边各写一套口径时，同一张单会出现「卡片 10150 / 账单 6150」，或者挂单期间
 * 「卡片停表、账单还在长钱」。
 *
 * <p>本用例不 mock 这两条计算链路：{@link KtvSessionApplicationService}（实时房费与当前会话挑选）、
 * {@link OrderAmountApplicationService}（合计基数与差额法）、{@link BillApplicationService}（账单）
 * 都用**真实实现**，只把 mapper / 计价方案 provider 换成桩，然后对同一份数据同时调用
 * {@code GET /business/orders/{id}}（投影）与 {@code GET /business/orders/{id}/bill}（账单），断言：
 * <ol>
 *   <li>{@code 卡片 liveTotalAmount == 账单 totalAmount}；</li>
 *   <li>账单自身自洽：{@code sum(items) + sum(servers) + roomFee.amount == totalAmount}；</li>
 *   <li>合计构成自洽：{@code subtotalAmount − discountAmount + taxAmount == totalAmount}。</li>
 * </ol>
 *
 * <p>覆盖状态：OPEN（含房费快照缺失/重复）、PAUSED（停表）、RESERVED（未开台/从未开台）、
 * CLOSED（结台固化）、无会话、一口价 PACKAGE、服务人员计时费、库内合计为 0 的存量脏数据、
 * 已取消且无 closed_at 的历史固化房费（线上订单 72）。
 */
class OrderBillBoardConsistencyTest {

    private static final long TENANT_ID = 100L;
    private static final long STORE_ID = 100L;
    private static final long ORDER_ID = 73L;

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final KtvSessionMapper ktvSessionMapper = mock(KtvSessionMapper.class);
    private final KtvServerSessionMapper serverSessionMapper = mock(KtvServerSessionMapper.class);
    private final KtvPricingPlanProvider pricingPlanProvider = mock(KtvPricingPlanProvider.class);

    private final OrderAmountApplicationService orderAmounts =
            new OrderAmountApplicationService(orderMapper, orderItemMapper);
    private final KtvSessionApplicationService sessionService = new KtvSessionApplicationService(
            ktvSessionMapper, orderMapper, orderItemMapper, pricingPlanProvider, AuditClient.disabled(), orderAmounts);
    private final BillApplicationService billService = new BillApplicationService(
            orderMapper, orderItemMapper, ktvSessionMapper, serverSessionMapper, pricingPlanProvider, null);
    private final OrderController controller = new OrderController(
            orderMapper,
            sessionService,
            mock(KtvServerSessionApplicationService.class),
            mock(ResourceStateClient.class),
            AuditClient.disabled(),
            mock(OrderCancellationApplicationService.class),
            mock(DailySerialNumberGenerator.class),
            mock(CustomerLookupMapper.class));

    @BeforeEach
    void setUp() {
        // 单元测试没有 MyBatis 启动过程：会话/订单的 Lambda 查询需要手工装 TableInfo。
        TableInfo orderInfo = TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderPo.class);
        LambdaUtils.installCache(orderInfo);
        TableInfo sessionInfo = TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KtvSessionPo.class);
        LambdaUtils.installCache(sessionInfo);
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, STORE_ID, 7L, 1, List.of("order.view")));
        when(pricingPlanProvider.resolve(any(), any())).thenReturn(plan());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    // —— OPEN ——

    /** 开台中 + 库里**没有**房费明细（存量订单）：两边都按实时房费算，且实时房费进合计。 */
    @Test
    void openSessionWithoutStoredRoomFeeAgrees() {
        OrderPo order = order(new BigDecimal("2150"), "SERVING");
        List<OrderItemPo> items = List.of(product(86L, "果盘", new BigDecimal("2150")));
        KtvSessionPo session = session("OPEN", LocalDateTime.now().minusMinutes(40), null, plan());
        fixture(order, items, List.of(session), List.of());

        assertConsistent(order, 2150L + 3000L, "OPEN 无房费明细");
    }

    /** 开台中 + 库里有「上次刷新」的房费快照（线上订单 73 的形态）：差额法两边同值。 */
    @Test
    void openSessionWithStaleRoomFeeSnapshotAgrees() {
        OrderPo order = order(new BigDecimal("10150"), "SERVING");
        List<OrderItemPo> items = List.of(
                roomFee(85L, "8000", "4000", "2", plan()),
                product(86L, "果盘", new BigDecimal("2150")));
        KtvSessionPo session = session("OPEN", LocalDateTime.now().minusMinutes(40), null, plan());
        fixture(order, items, List.of(session), List.of());

        // 实时房费 40 分钟 = 2 块 × 1500 = 3000；10150 − 8000 + 3000 = 5150
        assertConsistent(order, 5150L, "OPEN 有房费快照");
    }

    /** 开台中 + 房费明细重复（历史脏数据）：多行一并求和，两边仍然同值。 */
    @Test
    void openSessionWithDuplicatedRoomFeeRowsAgrees() {
        OrderPo order = order(new BigDecimal("6000"), "SERVING");
        List<OrderItemPo> items = List.of(
                roomFee(85L, "4000", "4000", "1", plan()),
                roomFee(86L, "2000", "2000", "1", plan()));
        KtvSessionPo session = session("OPEN", LocalDateTime.now().minusMinutes(40), null, plan());
        fixture(order, items, List.of(session), List.of());

        // 6000 − 6000 + 3000 = 3000
        assertConsistent(order, 3000L, "OPEN 房费明细重复");
    }

    // —— PAUSED（停表） ——

    /**
     * 挂单：账单与卡片都必须停在 pause_started_at。
     * 开台 T-150min、暂停于 T-120min（计费 30 分钟 = 1 块 = 1500），库内快照是 3000（暂停前写的）。
     * 若账单仍按 now 计费，它会算出 150 分钟 = 5 块 = 7500，合计 5150 − 3000 + 7500 = 9650——本用例锁死这个口径。
     */
    @Test
    void pausedSessionStopsBillingAtPauseStartedAtOnBothSurfaces() {
        OrderPo order = order(new BigDecimal("5150"), "SERVING");
        List<OrderItemPo> items = List.of(
                roomFee(85L, "3000", "1500", "2", plan()),
                product(86L, "果盘", new BigDecimal("2150")));
        KtvSessionPo session = session("PAUSED", LocalDateTime.now().minusMinutes(150), null, plan());
        session.setPauseStartedAt(LocalDateTime.now().minusMinutes(120));
        fixture(order, items, List.of(session), List.of());

        // 5150 − 3000 + 1500 = 3650（不是 9650）
        OrderPo board = assertConsistent(order, 3650L, "PAUSED 停表");
        assertEquals(1800L, board.getRoomElapsedSeconds(), "看板计时同样停在暂停时刻");

        BillResult bill = billService.buildBill(ORDER_ID);
        assertEquals(1800L, bill.roomFee().durationSeconds());
        assertEquals(1L, bill.roomFee().quantity());
        assertEquals("LIVE", bill.roomFee().source());
    }

    // —— RESERVED（未开台） ——

    /** 已开单未开台（RESERVED）：没有计费起点，两边都取库内合计，不产生房费行。 */
    @Test
    void reservedSessionKeepsStoredTotalOnBothSurfaces() {
        OrderPo order = order(new BigDecimal("2150"), "DRAFT");
        List<OrderItemPo> items = List.of(product(86L, "果盘", new BigDecimal("2150")));
        KtvSessionPo session = new KtvSessionPo();
        session.setId(58L);
        session.setTenantId(TENANT_ID);
        session.setOrderId(ORDER_ID);
        session.setStatus("RESERVED");
        session.setPausedSeconds(0);
        fixture(order, items, List.of(session), List.of());

        assertConsistent(order, 2150L, "RESERVED 未开台");
        assertNull(billService.buildBill(ORDER_ID).roomFee());
    }

    /**
     * 从未开台就被取消（线上订单 38 的会话形态：billing_start_at 为空）+ 库内合计停在 0：
     * 合计基数按「已生效明细合计」回退，卡片不再显示 0。
     */
    @Test
    void cancelledNeverOpenedSessionWithZeroStoredTotalFallsBackToItems() {
        OrderPo order = order(BigDecimal.ZERO, "VOIDED");
        List<OrderItemPo> items = List.of(
                product(25L, "可乐", new BigDecimal("3000")),
                product(26L, "洋酒", new BigDecimal("4800")));
        KtvSessionPo session = new KtvSessionPo();
        session.setId(25L);
        session.setTenantId(TENANT_ID);
        session.setOrderId(ORDER_ID);
        session.setStatus("CANCELLED");
        session.setPausedSeconds(0);
        fixture(order, items, List.of(session), List.of());

        assertConsistent(order, 7800L, "库内合计为 0 的存量数据");
    }

    // —— CLOSED（结台固化） ——

    /** 已结台：金额固化不再随时间变化，两边都取固化明细值。 */
    @Test
    void closedSessionKeepsFrozenAmountOnBothSurfaces() {
        OrderPo order = order(new BigDecimal("10150"), "WAITING_PAYMENT");
        List<OrderItemPo> items = List.of(
                roomFee(85L, "8000", "4000", "2", plan()),
                product(86L, "果盘", new BigDecimal("2150")));
        KtvSessionPo session = session("CLOSED", LocalDateTime.now().minusHours(3), LocalDateTime.now(), plan());
        fixture(order, items, List.of(session), List.of());

        OrderPo board = assertConsistent(order, 10150L, "CLOSED 结台固化");
        BillResult bill = billService.buildBill(ORDER_ID);
        assertEquals("CLOSED", bill.roomFee().source());
        assertTrue(bill.roomFee().durationKnown());
        assertEquals(8000L, bill.roomFee().amount());
        assertEquals(0, board.getLiveTotalAmount().compareTo(new BigDecimal("10150")),
                "结台后金额不再随时间变化");
    }

    /** 多会话订单（同一订单多次开台）：两边都必须取「当前这一次」（进行中的），不能取到旧会话。 */
    @Test
    void multiSessionOrderPicksTheCurrentSessionOnBothSurfaces() {
        OrderPo order = order(new BigDecimal("2150"), "SERVING");
        List<OrderItemPo> items = List.of(product(86L, "果盘", new BigDecimal("2150")));
        KtvSessionPo olderClosed = session("CLOSED", LocalDateTime.now().minusHours(5),
                LocalDateTime.now().minusHours(4), plan());
        olderClosed.setId(11L);
        KtvSessionPo currentOpen = session("OPEN", LocalDateTime.now().minusMinutes(40), null, plan());
        currentOpen.setId(12L);
        // 故意把已结束会话排在前面：findFirst 会取错。
        fixture(order, items, List.of(olderClosed, currentOpen), List.of());

        assertConsistent(order, 2150L + 3000L, "多会话订单取当前这一次");
    }

    // —— 无会话 / 一口价 / 服务人员 / 历史脏数据 ——

    /** 没有会话（非 KTV 单）：库内合计即终值，两边同值。 */
    @Test
    void orderWithoutSessionAgrees() {
        OrderPo order = order(new BigDecimal("2150"), "COMPLETED");
        fixture(order, List.of(product(86L, "果盘", new BigDecimal("2150"))), List.of(), List.of());

        assertConsistent(order, 2150L, "无会话");
    }

    /**
     * 一口价套餐（PACKAGE 不按时长计费）：实时估算为空，两边都取库内合计；
     * 账单不造 0 元房费行（否则用户看到一个解释不了的「包厢费 0」）。
     */
    @Test
    void packageOrderHasNoLiveRoomFeeOnBothSurfaces() {
        OrderPo order = order(new BigDecimal("26800"), "SERVING");
        List<OrderItemPo> items = List.of(
                product(86L, "果盘", new BigDecimal("6800")),
                item(87L, "PACKAGE_FEE", "欢唱套餐", new BigDecimal("20000"), new BigDecimal("1"),
                        new BigDecimal("20000"), plan()));
        KtvSessionPo session = session("OPEN", LocalDateTime.now().minusMinutes(90), null, packagePlan());
        fixture(order, items, List.of(session), List.of());

        OrderPo board = assertConsistent(order, 26800L, "一口价套餐");
        assertNull(board.getRoomEstimatedFee(), "一口价套餐没有实时房费估算");
        assertNull(billService.buildBill(ORDER_ID).roomFee());
    }

    /** 服务人员计时费：服务人员行独立展示，合计含它，两边同值且不重不漏。 */
    @Test
    void serverSessionFeeIsCountedOnBothSurfaces() {
        OrderPo order = order(new BigDecimal("10500"), "WAITING_PAYMENT");
        OrderItemPo serverFee = item(87L, "SERVICE", "额外服务人员#9", new BigDecimal("5000"),
                new BigDecimal("2"), new BigDecimal("10000"), plan());
        serverFee.setResourceId(9L);
        serverFee.setPriceSnapshotJson(plan().toSnapshotJson());
        List<OrderItemPo> items = List.of(product(86L, "果盘", new BigDecimal("500")), serverFee);
        KtvSessionPo session = session("CLOSED", LocalDateTime.now().minusHours(3), LocalDateTime.now(), plan());
        KtvServerSessionPo serverSession = new KtvServerSessionPo();
        serverSession.setId(1L);
        serverSession.setTenantId(TENANT_ID);
        serverSession.setOrderId(ORDER_ID);
        serverSession.setKtvSessionId(58L);
        serverSession.setServerResourceId(9L);
        serverSession.setStatus("ENDED");
        serverSession.setDurationSeconds(3600);
        serverSession.setIncrementMinutes(30);
        serverSession.setRoundingDirection(KtvRoundingDirection.CONSUMER_FAVOR.name());
        serverSession.setPricePerInc(5000L);
        serverSession.setTotalAmount(new BigDecimal("10000"));
        fixture(order, items, List.of(session), List.of(serverSession));

        assertConsistent(order, 10500L, "服务人员计时费");
        BillResult bill = billService.buildBill(ORDER_ID);
        assertEquals(1, bill.servers().size());
        assertEquals(10000L, bill.servers().getFirst().amount());
        assertEquals(2L, bill.servers().getFirst().quantity(), "块数一并下发，页面不需要自己算");
    }

    /**
     * 线上订单 72 的真实形态：订单已 VOIDED，会话 CANCELLED 且 closed_at IS NULL，
     * 但订单上仍留着 ROOM_FEE 明细 ¥40.00。金额两边同值（¥40.00，一分不动），
     * 且房费行明确标注「历史固化值 / 时长不可知」，不再是刺眼的「0 分钟收 40」。
     */
    @Test
    void voidedOrderWithHistoricalRoomFeeExplainsItselfOnBothSurfaces() {
        OrderPo order = order(new BigDecimal("4000"), "VOIDED");
        OrderItemPo roomFee = roomFee(85L, "4000", "4000", "1", plan());
        roomFee.setUpdatedAt(LocalDateTime.of(2026, 9, 20, 7, 22, 15));
        KtvSessionPo session = session("CANCELLED", LocalDateTime.of(2026, 9, 20, 6, 58, 48), null, plan());
        fixture(order, List.of(roomFee), List.of(session), List.of());

        assertConsistent(order, 4000L, "已作废 + 无结台时刻的历史房费");

        BillResult bill = billService.buildBill(ORDER_ID);
        assertFalse(bill.roomFee().durationKnown(), "没有结台时刻就不能声称时长（更不能用 0 分钟表示不可知）");
        assertEquals("HISTORICAL", bill.roomFee().source());
        assertEquals(1L, bill.roomFee().quantity());
        assertEquals(4000L, bill.roomFee().unitPrice(), "金额 = 块数 × 每递增粒度单价，页面可自证");
        assertEquals("2026-09-20T07:22:15", bill.roomFee().snapshotAt());
    }

    // —— 断言与装配 ——

    /**
     * 同一张单同时取「订单投影（收银台卡片/订单列表）」与「账单」，断言三件事同值：
     * 卡片 == 账单；账单明细之和 == 账单合计；原价合计 − 优惠 + 税 == 合计。
     */
    private OrderPo assertConsistent(OrderPo order, long expectedMinor, String state) {
        OrderPo board = controller.detail(order.getId());
        BillResult bill = billService.buildBill(order.getId());

        assertNotNull(board.getLiveTotalAmount(), state + "：卡片必须给出可直接展示的合计");
        assertEquals(0, board.getLiveTotalAmount().compareTo(BigDecimal.valueOf(expectedMinor)),
                state + "：卡片合计应为 " + expectedMinor);
        assertEquals(expectedMinor, bill.totalAmount(), state + "：账单合计应为 " + expectedMinor);
        assertEquals(board.getLiveTotalAmount().longValueExact(), bill.totalAmount(),
                state + "：收银台卡片与账单必须同值");

        long lines = (bill.roomFee() == null ? 0L : bill.roomFee().amount())
                + bill.items().stream().mapToLong(BillResult.ItemLine::amount).sum()
                + bill.servers().stream().mapToLong(BillResult.ServerLine::amount).sum();
        assertEquals(bill.totalAmount(), lines, state + "：账单明细之和必须等于合计（不重不漏）");
        assertEquals(bill.totalAmount(), bill.subtotalAmount() - bill.discountAmount() + bill.taxAmount(),
                state + "：合计的构成必须对得上（原价合计 − 优惠 + 税）");
        return board;
    }

    /** 一份数据装配：订单 + 全部 ACTIVE 明细 + 会话（+ 服务人员会话）。 */
    private void fixture(OrderPo order, List<OrderItemPo> items, List<KtvSessionPo> sessions,
                         List<KtvServerSessionPo> serverSessions) {
        when(orderMapper.selectById(order.getId())).thenReturn(order);
        when(orderItemMapper.selectList(any())).thenReturn(items);
        when(orderItemMapper.selectActiveItemsByOrderIds(any())).thenReturn(items);
        when(ktvSessionMapper.selectList(any())).thenReturn(sessions);
        when(serverSessionMapper.selectList(any())).thenReturn(serverSessions);
    }

    private static OrderPo order(BigDecimal totalAmount, String status) {
        OrderPo po = new OrderPo();
        po.setId(ORDER_ID);
        po.setTenantId(TENANT_ID);
        po.setStoreId(STORE_ID);
        po.setStatus(status);
        po.setCurrencyCode("CNY");
        po.setSubtotalAmount(totalAmount);
        po.setDiscountAmount(BigDecimal.ZERO);
        po.setTaxAmount(BigDecimal.ZERO);
        po.setTotalAmount(totalAmount);
        po.setPaidAmount(BigDecimal.ZERO);
        po.setRefundableAmount(BigDecimal.ZERO);
        return po;
    }

    private static KtvSessionPo session(String status, LocalDateTime billingStartAt, LocalDateTime closedAt,
                                        KtvPricingPlan plan) {
        KtvSessionPo po = new KtvSessionPo();
        po.setId(59L);
        po.setTenantId(TENANT_ID);
        po.setOrderId(ORDER_ID);
        po.setStatus(status);
        po.setOpenedAt(billingStartAt);
        po.setBillingStartAt(billingStartAt);
        po.setClosedAt(closedAt);
        po.setPausedSeconds(0);
        po.setBillingRuleSnapshotJson(plan.toSnapshotJson());
        return po;
    }

    private static OrderItemPo product(Long id, String name, BigDecimal amount) {
        return item(id, "PRODUCT", name, amount, BigDecimal.ONE, amount, null);
    }

    private static OrderItemPo roomFee(Long id, String totalAmount, String unitPrice, String quantity,
                                       KtvPricingPlan plan) {
        OrderItemPo po = item(id, "ROOM_FEE", "包厢费（含 1 名服务人员）", new BigDecimal(unitPrice),
                new BigDecimal(quantity), new BigDecimal(totalAmount), plan);
        po.setUpdatedAt(LocalDateTime.now());
        return po;
    }

    private static OrderItemPo item(Long id, String itemType, String name, BigDecimal unitPrice,
                                    BigDecimal quantity, BigDecimal totalAmount, KtvPricingPlan plan) {
        OrderItemPo po = new OrderItemPo();
        po.setId(id);
        po.setTenantId(TENANT_ID);
        po.setOrderId(ORDER_ID);
        po.setItemType(itemType);
        po.setNameSnapshot(name);
        po.setUnitPrice(unitPrice);
        po.setQuantity(quantity);
        po.setTotalAmount(totalAmount);
        po.setStatus("ACTIVE");
        po.setCurrencyCode("CNY");
        po.setDiscountAmount(BigDecimal.ZERO);
        po.setTaxAmount(BigDecimal.ZERO);
        if (plan != null) {
            po.setPriceSnapshotJson(plan.toSnapshotJson());
        }
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        return po;
    }

    /** 门店方案：¥30/小时（每 30 分钟一档 = ¥15.00），标准时长 120 分钟，超时 1.5 倍。 */
    private static KtvPricingPlan plan() {
        return new KtvPricingPlan(KtvBillingUnit.HOUR, 3000L, 120, 0, new BigDecimal("1.5"), 30,
                KtvRoundingDirection.CONSUMER_FAVOR, 5000L);
    }

    /** 一口价套餐：计费单位 PACKAGE（不按时长递增）。 */
    private static KtvPricingPlan packagePlan() {
        return new KtvPricingPlan(KtvBillingUnit.PACKAGE, 20000L, 240, 0, BigDecimal.ONE, 30,
                KtvRoundingDirection.CONSUMER_FAVOR, 0L);
    }
}

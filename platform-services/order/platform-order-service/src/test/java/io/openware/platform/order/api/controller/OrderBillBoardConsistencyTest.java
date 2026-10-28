package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.dto.OrderView;
import io.openware.platform.order.application.BillApplicationService;
import io.openware.platform.order.application.DailySerialNumberGenerator;
import io.openware.platform.order.application.KtvServerSessionApplicationService;
import io.openware.platform.order.application.KtvSessionApplicationService;
import io.openware.platform.order.application.OrderAmountApplicationService;
import io.openware.platform.order.application.OrderCancellationApplicationService;
import io.openware.platform.order.application.dto.BillResult;
import io.openware.platform.order.domain.ktv.model.KtvBillingUnit;
import io.openware.platform.order.domain.ktv.model.KtvPricingPlan;
import io.openware.platform.order.domain.ktv.model.KtvRoundingDirection;
import io.openware.platform.order.domain.ktv.model.KtvServerSession;
import io.openware.platform.order.domain.ktv.model.KtvSession;
import io.openware.platform.order.domain.ktv.repository.KtvPricingPlanProvider;
import io.openware.platform.order.domain.ktv.repository.KtvServerSessionRepository;
import io.openware.platform.order.domain.ktv.repository.KtvSessionRepository;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.domain.order.repository.MemberLookupProvider;
import io.openware.platform.order.domain.order.repository.OrderItemRepository;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.model.OrderItem;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.model.RefundStatus;
import io.openware.platform.order.domain.order.repository.AuditSnapshot;
import io.openware.platform.order.domain.order.repository.Persisted;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;
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

    private final OrderRepository orderMapper = mock(OrderRepository.class);
    private final OrderItemRepository orderItemMapper = mock(OrderItemRepository.class);
    private final KtvSessionRepository ktvSessionRepository = mock(KtvSessionRepository.class);
    private final KtvServerSessionRepository serverSessionRepository = mock(KtvServerSessionRepository.class);
    private final KtvPricingPlanProvider pricingPlanProvider = mock(KtvPricingPlanProvider.class);

    private final OrderAmountApplicationService orderAmounts =
            new OrderAmountApplicationService(orderMapper, orderItemMapper);
    private final KtvSessionApplicationService sessionService = new KtvSessionApplicationService(
            ktvSessionRepository, orderMapper, orderItemMapper, pricingPlanProvider, AuditClient.disabled(),
            orderAmounts);
    private final BillApplicationService billService = new BillApplicationService(
            orderMapper, orderItemMapper, ktvSessionRepository, serverSessionRepository, pricingPlanProvider, null);
    private final OrderController controller = OrderControllerTestFactory.create(
            orderMapper,
            sessionService,
            mock(KtvServerSessionApplicationService.class),
            mock(ResourceStateClient.class),
            AuditClient.disabled(),
            mock(OrderCancellationApplicationService.class),
            mock(DailySerialNumberGenerator.class),
            mock(MemberLookupProvider.class));

    @BeforeEach
    void setUp() {
        // 订单/明细的查询条件现在收在 OrderQuery/OrderItemQuery 端口里，不再有 Lambda 列引用，
        // 因此不再需要手工装 TableInfo 缓存（SQL 形状由 OrderRepositoryAdapterTest 钉住）。
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
        Order order = order(new BigDecimal("2150"), "DRAFT");
        List<Persisted<OrderItem>> items = List.of(product(86L, "果盘", new BigDecimal("2150")));
        KtvSession session = session("OPEN", LocalDateTime.now().minusMinutes(40), null, plan());
        fixture(order, items, List.of(session), List.of());

        assertConsistent(order, 2150L + 3000L, "OPEN 无房费明细");
    }

    /** 开台中 + 库里有「上次刷新」的房费快照（线上订单 73 的形态）：差额法两边同值。 */
    @Test
    void openSessionWithStaleRoomFeeSnapshotAgrees() {
        Order order = order(new BigDecimal("10150"), "DRAFT");
        List<Persisted<OrderItem>> items = List.of(
                roomFee(85L, "8000", "4000", "2", plan()),
                product(86L, "果盘", new BigDecimal("2150")));
        KtvSession session = session("OPEN", LocalDateTime.now().minusMinutes(40), null, plan());
        fixture(order, items, List.of(session), List.of());

        // 实时房费 40 分钟 = 2 块 × 1500 = 3000；10150 − 8000 + 3000 = 5150
        assertConsistent(order, 5150L, "OPEN 有房费快照");
    }

    /** 开台中 + 房费明细重复（历史脏数据）：多行一并求和，两边仍然同值。 */
    @Test
    void openSessionWithDuplicatedRoomFeeRowsAgrees() {
        Order order = order(new BigDecimal("6000"), "DRAFT");
        List<Persisted<OrderItem>> items = List.of(
                roomFee(85L, "4000", "4000", "1", plan()),
                roomFee(86L, "2000", "2000", "1", plan()));
        KtvSession session = session("OPEN", LocalDateTime.now().minusMinutes(40), null, plan());
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
        Order order = order(new BigDecimal("5150"), "DRAFT");
        List<Persisted<OrderItem>> items = List.of(
                roomFee(85L, "3000", "1500", "2", plan()),
                product(86L, "果盘", new BigDecimal("2150")));
        KtvSession session = session("PAUSED", LocalDateTime.now().minusMinutes(150), null, plan())
                .withPauseStartedAt(LocalDateTime.now().minusMinutes(120));
        fixture(order, items, List.of(session), List.of());

        // 5150 − 3000 + 1500 = 3650（不是 9650）
        OrderView board = assertConsistent(order, 3650L, "PAUSED 停表");
        assertEquals(1800L, board.roomElapsedSeconds(), "看板计时同样停在暂停时刻");

        BillResult bill = billService.buildBill(ORDER_ID);
        assertEquals(1800L, bill.roomFee().durationSeconds());
        assertEquals(1L, bill.roomFee().quantity());
        assertEquals("LIVE", bill.roomFee().source());
    }

    // —— RESERVED（未开台） ——

    /** 已开单未开台（RESERVED）：没有计费起点，两边都取库内合计，不产生房费行。 */
    @Test
    void reservedSessionKeepsStoredTotalOnBothSurfaces() {
        Order order = order(new BigDecimal("2150"), "DRAFT");
        List<Persisted<OrderItem>> items = List.of(product(86L, "果盘", new BigDecimal("2150")));
        // 已开单未开台：只有 RESERVED 状态，没有计费起点。
        KtvSession session = KtvSession.reserved(TENANT_ID, STORE_ID, "KTV", ORDER_ID, 3001L, null, null)
                .withId(58L);
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
        Order order = order(BigDecimal.ZERO, "VOIDED");
        List<Persisted<OrderItem>> items = List.of(
                product(25L, "可乐", new BigDecimal("3000")),
                product(26L, "洋酒", new BigDecimal("4800")));
        // 从未开台就被取消：billing_start_at 为空（没有计费起点）。
        KtvSession session = KtvSession.reserved(TENANT_ID, STORE_ID, "KTV", ORDER_ID, 3001L, null, null)
                .withId(25L)
                .withStatus(KtvSession.STATUS_CANCELLED);
        fixture(order, items, List.of(session), List.of());

        assertConsistent(order, 7800L, "库内合计为 0 的存量数据");
    }

    // —— CLOSED（结台固化） ——

    /** 已结台：金额固化不再随时间变化，两边都取固化明细值。 */
    @Test
    void closedSessionKeepsFrozenAmountOnBothSurfaces() {
        Order order = order(new BigDecimal("10150"), "PENDING_PAYMENT");
        List<Persisted<OrderItem>> items = List.of(
                roomFee(85L, "8000", "4000", "2", plan()),
                product(86L, "果盘", new BigDecimal("2150")));
        KtvSession session = session("CLOSED", LocalDateTime.now().minusHours(3), LocalDateTime.now(), plan());
        fixture(order, items, List.of(session), List.of());

        OrderView board = assertConsistent(order, 10150L, "CLOSED 结台固化");
        BillResult bill = billService.buildBill(ORDER_ID);
        assertEquals("CLOSED", bill.roomFee().source());
        assertTrue(bill.roomFee().durationKnown());
        assertEquals(8000L, bill.roomFee().amount());
        assertEquals(0, board.liveTotalAmount().compareTo(new BigDecimal("10150")),
                "结台后金额不再随时间变化");
    }

    /** 多会话订单（同一订单多次开台）：两边都必须取「当前这一次」（进行中的），不能取到旧会话。 */
    @Test
    void multiSessionOrderPicksTheCurrentSessionOnBothSurfaces() {
        Order order = order(new BigDecimal("2150"), "DRAFT");
        List<Persisted<OrderItem>> items = List.of(product(86L, "果盘", new BigDecimal("2150")));
        KtvSession olderClosed = session("CLOSED", LocalDateTime.now().minusHours(5),
                LocalDateTime.now().minusHours(4), plan()).withId(11L);
        KtvSession currentOpen = session("OPEN", LocalDateTime.now().minusMinutes(40), null, plan()).withId(12L);
        // 故意把已结束会话排在前面：findFirst 会取错。
        fixture(order, items, List.of(olderClosed, currentOpen), List.of());

        assertConsistent(order, 2150L + 3000L, "多会话订单取当前这一次");
    }

    // —— 无会话 / 一口价 / 服务人员 / 历史脏数据 ——

    /** 没有会话（非 KTV 单）：库内合计即终值，两边同值。 */
    @Test
    void orderWithoutSessionAgrees() {
        Order order = order(new BigDecimal("2150"), "COMPLETED");
        fixture(order, List.of(product(86L, "果盘", new BigDecimal("2150"))), List.of(), List.of());

        assertConsistent(order, 2150L, "无会话");
    }

    /**
     * 一口价套餐（PACKAGE 不按时长计费）：实时估算为空，两边都取库内合计；
     * 账单不造 0 元房费行（否则用户看到一个解释不了的「包厢费 0」）。
     */
    @Test
    void packageOrderHasNoLiveRoomFeeOnBothSurfaces() {
        Order order = order(new BigDecimal("26800"), "DRAFT");
        List<Persisted<OrderItem>> items = List.of(
                product(86L, "果盘", new BigDecimal("6800")),
                item(87L, "PACKAGE_FEE", "欢唱套餐", new BigDecimal("20000"), new BigDecimal("1"),
                        new BigDecimal("20000"), plan(), LocalDateTime.now()));
        KtvSession session = session("OPEN", LocalDateTime.now().minusMinutes(90), null, packagePlan());
        fixture(order, items, List.of(session), List.of());

        OrderView board = assertConsistent(order, 26800L, "一口价套餐");
        assertNull(board.roomEstimatedFee(), "一口价套餐没有实时房费估算");
        assertNull(billService.buildBill(ORDER_ID).roomFee());
    }

    /** 服务人员计时费：服务人员行独立展示，合计含它，两边同值且不重不漏。 */
    @Test
    void serverSessionFeeIsCountedOnBothSurfaces() {
        Order order = order(new BigDecimal("10500"), "PENDING_PAYMENT");
        // 服务人员计时费：SERVICE + resourceId=服务人员资源 + 价格快照（加项路径不写快照）。
        OrderItem serverFeeItem = new OrderItem(87L, TENANT_ID, STORE_ID, "KTV", ORDER_ID, "SERVICE", null, 9L,
                "额外服务人员#9", new BigDecimal("5000"), new BigDecimal("2"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("10000"), "CNY", plan().toSnapshotJson(), "ACTIVE", "MERCHANT", null, null,
                null, null, null, null);
        Persisted<OrderItem> serverFee = new Persisted<>(serverFeeItem, auditNow());
        List<Persisted<OrderItem>> items = List.of(product(86L, "果盘", new BigDecimal("500")), serverFee);
        KtvSession session = session("CLOSED", LocalDateTime.now().minusHours(3), LocalDateTime.now(), plan());
        // 服务人员点单（领域模型不可变，22 列逐项对应迁移前的持久化对象）。
        KtvServerSession serverSession = new KtvServerSession(1L, TENANT_ID, STORE_ID, "KTV", ORDER_ID, 58L, 9L,
                null, null, null, null, "HOUR", 30, KtvRoundingDirection.CONSUMER_FAVOR.name(), 5000L, 3600,
                null, new BigDecimal("10000"), null, null, "ENDED", 0);
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
        Order order = order(new BigDecimal("4000"), "VOIDED");
        // 明细的 updated_at 是房费金额的「固化时刻」（记账列，不进领域模型），由读路径的记账快照带回。
        Persisted<OrderItem> roomFee = roomFee(85L, "4000", "4000", "1", plan(),
                LocalDateTime.of(2026, 9, 20, 7, 22, 15));
        KtvSession session = session("CANCELLED", LocalDateTime.of(2026, 9, 20, 6, 58, 48), null, plan());
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
    private OrderView assertConsistent(Order order, long expectedMinor, String state) {
        OrderView board = controller.detail(order.id());
        BillResult bill = billService.buildBill(order.id());

        assertNotNull(board.liveTotalAmount(), state + "：卡片必须给出可直接展示的合计");
        assertEquals(0, board.liveTotalAmount().compareTo(BigDecimal.valueOf(expectedMinor)),
                state + "：卡片合计应为 " + expectedMinor);
        assertEquals(expectedMinor, bill.totalAmount(), state + "：账单合计应为 " + expectedMinor);
        assertEquals(board.liveTotalAmount().longValueExact(), bill.totalAmount(),
                state + "：收银台卡片与账单必须同值");

        long lines = (bill.roomFee() == null ? 0L : bill.roomFee().amount())
                + bill.items().stream().mapToLong(BillResult.ItemLine::amount).sum()
                + bill.servers().stream().mapToLong(BillResult.ServerLine::amount).sum();
        assertEquals(bill.totalAmount(), lines, state + "：账单明细之和必须等于合计（不重不漏）");
        assertEquals(bill.totalAmount(), bill.subtotalAmount() - bill.discountAmount() + bill.taxAmount(),
                state + "：合计的构成必须对得上（原价合计 − 优惠 + 税）");
        return board;
    }

    /** 一份数据装配：订单 + 全部 ACTIVE 明细（含记账快照）+ 会话（+ 服务人员会话）。 */
    private void fixture(Order order, List<Persisted<OrderItem>> items, List<KtvSession> sessions,
                         List<KtvServerSession> serverSessions) {
        when(orderMapper.findById(order.id())).thenReturn(Optional.of(order));
        when(orderItemMapper.list(any())).thenReturn(items);
        // 批量口径只回业务聚合（实时合计的回退基数与房费快照合计都不看记账列）。
        when(orderItemMapper.listActiveByOrderIds(any())).thenReturn(
                items.stream().map(Persisted::aggregate).toList());
        // 账单按单个订单取会话；订单投影按整页订单批量取会话——两个端口方法都要给。
        when(ktvSessionRepository.listByOrderId(any())).thenReturn(sessions);
        when(ktvSessionRepository.listByOrderIds(any())).thenReturn(sessions);
        when(serverSessionRepository.listByOrderId(any())).thenReturn(serverSessions);
    }

    private static Order order(BigDecimal totalAmount, String status) {
        return new Order(ORDER_ID, TENANT_ID, null, STORE_ID, "ORD-20260920-0001", null, "KTV", null, status, RefundStatus.NONE.name(), "CNY", totalAmount, BigDecimal.ZERO, BigDecimal.ZERO, totalAmount, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, 0);
    }

    private static KtvSession session(String status, LocalDateTime billingStartAt, LocalDateTime closedAt,
                                      KtvPricingPlan plan) {
        return KtvSession.reserved(TENANT_ID, STORE_ID, "KTV", ORDER_ID, 3001L, null, null)
                .withId(59L)
                .withStatus(status)
                .withOpenedAt(billingStartAt)
                .withBillingStartAt(billingStartAt)
                .withClosedAt(closedAt)
                .withBillingRuleSnapshotJson(plan.toSnapshotJson());
    }

    /** 记账快照：本用例只用到 {@code updated_at}（房费明细的固化时刻）。 */
    private static AuditSnapshot auditNow() {
        return new AuditSnapshot(null, LocalDateTime.now(), null, LocalDateTime.now());
    }

    private static Persisted<OrderItem> product(Long id, String name, BigDecimal amount) {
        return item(id, "PRODUCT", name, amount, BigDecimal.ONE, amount, null, null);
    }

    private static Persisted<OrderItem> roomFee(Long id, String totalAmount, String unitPrice, String quantity,
                                                KtvPricingPlan plan) {
        return roomFee(id, totalAmount, unitPrice, quantity, plan, LocalDateTime.now());
    }

    private static Persisted<OrderItem> roomFee(Long id, String totalAmount, String unitPrice, String quantity,
                                                KtvPricingPlan plan, LocalDateTime fixedAt) {
        return item(id, "ROOM_FEE", "包厢费（含 1 名服务人员）", new BigDecimal(unitPrice),
                new BigDecimal(quantity), new BigDecimal(totalAmount), plan, fixedAt);
    }

    private static Persisted<OrderItem> item(Long id, String itemType, String name, BigDecimal unitPrice,
                                             BigDecimal quantity, BigDecimal totalAmount, KtvPricingPlan plan,
                                             LocalDateTime fixedAt) {
        OrderItem item = new OrderItem(id, TENANT_ID, STORE_ID, "KTV", ORDER_ID, itemType, null, null, name,
                unitPrice, quantity, BigDecimal.ZERO, BigDecimal.ZERO, totalAmount, "CNY",
                plan == null ? null : plan.toSnapshotJson(), OrderItem.STATUS_ACTIVE,
                OrderItem.SOURCE_MERCHANT, null, null, null, null, null, null);
        return new Persisted<>(item, new AuditSnapshot(null, LocalDateTime.now(), null, fixedAt));
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

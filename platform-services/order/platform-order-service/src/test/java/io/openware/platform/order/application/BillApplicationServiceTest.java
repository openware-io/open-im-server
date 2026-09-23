package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.openware.platform.order.application.dto.BillResult;
import io.openware.platform.order.domain.ktv.model.KtvPricingPlan;
import io.openware.platform.order.domain.ktv.port.KtvPricingPlanProvider;
import io.openware.platform.order.infra.persistence.mapper.KtvServerSessionMapper;
import io.openware.platform.order.infra.persistence.mapper.KtvSessionMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderItemMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.po.KtvServerSessionPo;
import io.openware.platform.order.infra.persistence.po.KtvSessionPo;
import io.openware.platform.order.infra.persistence.po.OrderItemPo;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 客户消费账单完整性（F11）：账单 items 必须包含所有消费类明细（含 item_type=SERVICE 的服务目录项，
 * 如「服务员点歌」），且 sum(items) + sum(servers) + roomFee == totalAmount（不重不漏）。
 */
class BillApplicationServiceTest {

  private final OrderMapper orderMapper = mock(OrderMapper.class);
  private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
  private final KtvSessionMapper ktvSessionMapper = mock(KtvSessionMapper.class);
  private final KtvServerSessionMapper serverSessionMapper = mock(KtvServerSessionMapper.class);
  private final KtvPricingPlanProvider pricingPlanProvider = mock(KtvPricingPlanProvider.class);

  private final BillApplicationService service = new BillApplicationService(
      orderMapper, orderItemMapper, ktvSessionMapper, serverSessionMapper, pricingPlanProvider, null);

  @Test
  void billIncludesAllConsumptionLinesAndBalancesToTotal() {
    OrderPo order = order(BigDecimal.valueOf(26500));
    when(orderMapper.selectById(1L)).thenReturn(order);
    // 服务人员计时费明细与结台写入的一致：SERVICE + resourceId + price_snapshot_json
    OrderItemPo serverFeeItem = item(3L, "SERVICE", "服务人员#9", 9L, new BigDecimal("5000"), new BigDecimal("2"), new BigDecimal("10000"));
    serverFeeItem.setPriceSnapshotJson("{\"serverPricePerInc\":5000}");
    when(orderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
        item(1L, "PRODUCT", "百威啤酒", null, new BigDecimal("500"), new BigDecimal("1"), new BigDecimal("500")),
        // 服务目录项（服务员点歌）：item_type=SERVICE，但不是服务人员计时费，必须出现在 items 里
        item(2L, "SERVICE", "服务员点歌", null, new BigDecimal("1000"), new BigDecimal("1"), new BigDecimal("1000")),
        serverFeeItem,
        item(4L, "ROOM_FEE", "包厢计时费", null, new BigDecimal("5000"), new BigDecimal("3"), new BigDecimal("15000"))));
    when(ktvSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(closedSession()));
    when(serverSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(serverSession()));

    BillResult bill = service.buildBill(1L);

    // 1) items 覆盖商品 + 服务目录项，不再把 SERVICE 一律过滤掉
    assertEquals(List.of("百威啤酒", "服务员点歌"),
        bill.items().stream().map(BillResult.ItemLine::name).toList());

    // 2) 服务人员计时费保留独立 servers 行，且不在 items 里重复计数
    assertEquals(1, bill.servers().size());
    assertEquals(10000L, bill.servers().getFirst().amount());

    // 3) 包厢费独立分区
    assertNotNull(bill.roomFee());
    assertEquals(15000L, bill.roomFee().amount());

    // 4) 不重不漏：sum(items) + sum(servers) + roomFee == totalAmount
    long itemsSum = bill.items().stream().mapToLong(BillResult.ItemLine::amount).sum();
    long serversSum = bill.servers().stream().mapToLong(BillResult.ServerLine::amount).sum();
    assertEquals(bill.totalAmount(), itemsSum + serversSum + bill.roomFee().amount());
    assertEquals(26500L, itemsSum + serversSum + bill.roomFee().amount());
  }

  /**
   * 加项里同样可能有 item_type=SERVICE 且带 resourceId 的「服务员点歌」明细（加项路径不写 price_snapshot_json），
   * 它不能因为 resourceId 恰好命中某个服务人员会话就从 items 里消失，否则明细之和会少算这笔钱。
   */
  @Test
  void serviceAddOnWithResourceIdStaysInItems() {
    OrderPo order = order(BigDecimal.valueOf(11000));
    when(orderMapper.selectById(1L)).thenReturn(order);
    OrderItemPo serverFeeItem = item(1L, "SERVICE", "服务人员#9", 9L, new BigDecimal("5000"), new BigDecimal("2"), new BigDecimal("10000"));
    serverFeeItem.setPriceSnapshotJson("{\"serverPricePerInc\":5000}");
    OrderItemPo addOn = item(2L, "SERVICE", "服务员点歌", 9L, new BigDecimal("1000"), new BigDecimal("1"), new BigDecimal("1000"));
    when(orderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(serverFeeItem, addOn));
    when(ktvSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
    when(serverSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(serverSession()));

    BillResult bill = service.buildBill(1L);

    assertEquals(List.of("服务员点歌"), bill.items().stream().map(BillResult.ItemLine::name).toList());
    long itemsSum = bill.items().stream().mapToLong(BillResult.ItemLine::amount).sum();
    long serversSum = bill.servers().stream().mapToLong(BillResult.ServerLine::amount).sum();
    assertEquals(bill.totalAmount(), itemsSum + serversSum);
    assertEquals(11000L, itemsSum + serversSum);
  }

  /** 没有已结台会话但有房费明细（异常/历史数据）时，房费仍要出现在账单里，合计不能丢项。 */
  @Test
  void roomFeeItemsAreShownEvenWithoutClosedSession() {
    OrderPo order = order(BigDecimal.valueOf(15000));
    when(orderMapper.selectById(1L)).thenReturn(order);
    when(orderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
        item(1L, "ROOM_FEE", "包厢计时费", null, new BigDecimal("5000"), new BigDecimal("3"), new BigDecimal("15000"))));
    when(ktvSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
    when(serverSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

    BillResult bill = service.buildBill(1L);

    assertNotNull(bill.roomFee());
    assertEquals(15000L, bill.roomFee().amount());
    assertEquals(bill.totalAmount(), bill.roomFee().amount());
  }

  // —— 包厢费行的「怎么算出来的」：账单必须自证，且不再输出「0 分钟 + 有金额」的自相矛盾行 ——

  /**
   * 挂单（PAUSED）停表：账单实时值必须与看板/结台同一口径——截止到 pause_started_at，
   * 而不是 now（此前账单按 now 算，暂停期间还在长钱，同一张挂单看板一个数、账单另一个数）。
   */
  @Test
  void pausedSessionStopsBillingAtPauseStartedAt() {
    OrderPo order = order(BigDecimal.ZERO);
    order.setStatus("SERVING");
    when(orderMapper.selectById(1L)).thenReturn(order);
    when(orderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
    KtvSessionPo paused = openSession();
    paused.setStatus("PAUSED");
    // 开台 10:00 → 暂停 10:30（停表）→ 现在 12:30：计费时长只能是 30 分钟 = 1 块。
    paused.setBillingStartAt(LocalDateTime.now().minusMinutes(150));
    paused.setPauseStartedAt(LocalDateTime.now().minusMinutes(120));
    paused.setBillingRuleSnapshotJson(plan().toSnapshotJson());
    when(ktvSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(paused));
    when(serverSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

    BillResult bill = service.buildBill(1L);

    assertEquals("PAUSED", paused.getStatus());
    assertEquals(1800L, bill.roomFee().durationSeconds(), "暂停期间不计费：时长停在暂停时刻");
    assertEquals(1500L, bill.roomFee().amount(), "30 分钟 → 1 块 × ¥15.00（方案 ¥30.00/小时、30 分钟一档）");
    assertEquals(1L, bill.roomFee().quantity());
    assertEquals("LIVE", bill.roomFee().source());
    assertTrue(bill.roomFee().live(), "挂单中的账单仍是实时值");
    assertEquals(1500L, bill.totalAmount());
  }

  /**
   * 历史固化明细但**没有结台时刻**（线上订单 72：作废单 + CANCELLED 会话、closed_at IS NULL，
   * 明细仍留 ¥40.00）：不能再输出「计费时长 0 分钟 + 金额 40」——那行字用户没法解释。
   * 口径：来源标成 HISTORICAL、durationKnown=false（页面说「未记录结台时刻」而不是「0 分钟」），
   * 金额由块数 × 每递增粒度单价 + 固化时刻自证。
   */
  @Test
  void historicalRoomFeeWithoutClosedAtExplainsItselfByBlocksAndSnapshotTime() {
    OrderPo order = order(BigDecimal.valueOf(4000));
    order.setStatus("VOIDED");
    when(orderMapper.selectById(1L)).thenReturn(order);
    OrderItemPo roomFee = item(1L, "ROOM_FEE", "包厢费（含 1 名服务人员）", null,
        new BigDecimal("4000"), new BigDecimal("1"), new BigDecimal("4000"));
    roomFee.setPriceSnapshotJson(plan().toSnapshotJson());
    roomFee.setUpdatedAt(LocalDateTime.of(2026, 9, 20, 7, 22, 15));
    when(orderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(roomFee));
    KtvSessionPo cancelled = new KtvSessionPo();
    cancelled.setId(58L);
    cancelled.setTenantId(100L);
    cancelled.setOrderId(1L);
    cancelled.setStatus("CANCELLED");
    cancelled.setBillingStartAt(LocalDateTime.of(2026, 9, 20, 6, 58, 48));
    cancelled.setClosedAt(null);
    cancelled.setPausedSeconds(0);
    cancelled.setBillingRuleSnapshotJson(plan().toSnapshotJson());
    when(ktvSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(cancelled));
    when(serverSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

    BillResult bill = service.buildBill(1L);

    assertEquals("HISTORICAL", bill.roomFee().source());
    assertFalse(bill.roomFee().durationKnown(), "没有结台时刻：时长不可知，页面不得显示 0 分钟");
    assertEquals(0L, bill.roomFee().durationSeconds());
    assertEquals(1L, bill.roomFee().quantity(), "块数来自固化明细，金额可自证");
    assertEquals(4000L, bill.roomFee().unitPrice());
    assertEquals(4000L, bill.roomFee().amount());
    assertEquals("2026-09-20T07:22:15", bill.roomFee().snapshotAt(), "固化时刻是历史金额的唯一时间锚点");
    assertEquals("门店标准价", bill.roomFee().planName());
    assertEquals(4000L, bill.totalAmount(), "金额一分不动：只是把『怎么来的』讲清楚");
  }

  /** 结台固化（closed_at 已知）：时长可信，块数/单价/超时口径一并下发，前端不做任何算术。 */
  @Test
  void closedSessionExplainsDurationBlocksAndOvertime() {
    OrderPo order = order(BigDecimal.valueOf(12000));
    when(orderMapper.selectById(1L)).thenReturn(order);
    OrderItemPo roomFee = item(1L, "ROOM_FEE", "包厢计时费", null,
        new BigDecimal("1500"), new BigDecimal("6"), new BigDecimal("12000"));
    roomFee.setPriceSnapshotJson(plan().toSnapshotJson());
    roomFee.setUpdatedAt(LocalDateTime.of(2026, 9, 20, 10, 0));
    when(orderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(roomFee));
    KtvSessionPo closed = closedSession();
    // 标准时长 120 分钟；计费 180 分钟 → 超时 60 分钟（超时倍率 1.5：4 块 × 1.5 + ... 由明细固化，不在此复算）
    closed.setBillingStartAt(LocalDateTime.of(2026, 9, 20, 7, 0));
    closed.setClosedAt(LocalDateTime.of(2026, 9, 20, 10, 0));
    closed.setBillingRuleSnapshotJson(plan().toSnapshotJson());
    when(ktvSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(closed));
    when(serverSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

    BillResult bill = service.buildBill(1L);

    assertEquals("CLOSED", bill.roomFee().source());
    assertTrue(bill.roomFee().durationKnown());
    assertEquals(3 * 3600L, bill.roomFee().durationSeconds());
    assertEquals(120 * 60L, bill.roomFee().standardSeconds(), "标准时长用于单独说明超时部分");
    assertEquals(6L, bill.roomFee().quantity(), "块数取固化明细");
    assertEquals("1.5", bill.roomFee().overtimeRate());
    assertEquals(30, bill.roomFee().incrementMinutes());
    assertEquals(12000L, bill.roomFee().amount(), "已结台：金额取固化明细，一分不动");
    // 合计的构成可以一眼对上：原价合计 − 优惠 + 税 = 合计
    assertEquals(12000L, bill.subtotalAmount());
    assertEquals(0L, bill.discountAmount());
    assertEquals(0L, bill.taxAmount());
    assertEquals(bill.subtotalAmount() - bill.discountAmount() + bill.taxAmount(), bill.totalAmount());
  }

  /**
   * 一口价套餐（PACKAGE 不按时长计费、也不写房费明细）：账单不能凭空多一行「包厢费 0」，
   * 也不能因为「没有房费行」把库内合计里的钱减掉。
   */
  @Test
  void packagePlanHasNoRoomFeeLineAndKeepsStoredTotal() {
    OrderPo order = order(BigDecimal.valueOf(26800));
    order.setStatus("SERVING");
    when(orderMapper.selectById(1L)).thenReturn(order);
    when(orderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
        item(1L, "PRODUCT", "果盘", null, new BigDecimal("6800"), new BigDecimal("1"), new BigDecimal("6800")),
        item(2L, "PACKAGE_FEE", "欢唱套餐", null, new BigDecimal("20000"), new BigDecimal("1"), new BigDecimal("20000"))));
    KtvSessionPo open = openSession();
    open.setBillingRuleSnapshotJson(packagePlan().toSnapshotJson());
    when(ktvSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(open));
    when(serverSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

    BillResult bill = service.buildBill(1L);

    assertNull(bill.roomFee(), "一口价套餐没有计时房费行");
    assertEquals(26800L, bill.totalAmount(), "没有房费行时不得改动库内合计");
    assertEquals(2, bill.items().size());
  }

  /**
   * 多会话订单（同一订单多次开台）：账单必须与订单投影取**同一条**「当前这一次」会话——
   * 有进行中的会话时不能用上一次已结台会话的固化值（否则看板实时、账单是旧数）。
   */
  @Test
  void billPicksTheSameCurrentSessionAsOrderBoard() {
    OrderPo order = order(BigDecimal.ZERO);
    order.setStatus("SERVING");
    when(orderMapper.selectById(1L)).thenReturn(order);
    when(orderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
    KtvSessionPo olderClosed = closedSession();
    olderClosed.setId(11L);
    olderClosed.setOrderId(1L);
    KtvSessionPo currentOpen = openSession();
    currentOpen.setId(12L);
    currentOpen.setOrderId(1L);
    currentOpen.setBillingStartAt(LocalDateTime.now().minusMinutes(40));
    currentOpen.setBillingRuleSnapshotJson(plan().toSnapshotJson());
    // 故意把已结束会话放在前面：账单不能 findFirst 取到它。
    when(ktvSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(olderClosed, currentOpen));
    when(serverSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

    BillResult bill = service.buildBill(1L);

    assertEquals("LIVE", bill.roomFee().source(), "有进行中会话时必须按本次开台实时算");
    assertEquals(3000L, bill.roomFee().amount(), "40 分钟 → 2 块 × ¥15.00（不是旧会话的固化值）");
    assertEquals(3000L, bill.totalAmount());
  }

  /** 房费明细多行（历史脏数据）：金额/块数一并求和，与订单合计的重算口径一致（不重不漏）。 */
  @Test
  void duplicatedRoomFeeRowsAreSummedNotDropped() {
    OrderPo order = order(BigDecimal.valueOf(6000));
    when(orderMapper.selectById(1L)).thenReturn(order);
    when(orderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
        item(1L, "ROOM_FEE", "包厢计时费", null, new BigDecimal("4000"), new BigDecimal("1"), new BigDecimal("4000")),
        item(2L, "ROOM_FEE", "包厢计时费", null, new BigDecimal("2000"), new BigDecimal("1"), new BigDecimal("2000"))));
    when(ktvSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(closedSession()));
    when(serverSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

    BillResult bill = service.buildBill(1L);

    assertEquals(6000L, bill.roomFee().amount(), "多行一并求和");
    assertEquals(2L, bill.roomFee().quantity(), "块数同样求和，页面「单价 × 块数」对得上");
    assertEquals(bill.totalAmount(), bill.roomFee().amount(), "明细之和 = 合计（差额法为恒等变换）");
  }

  /** 读路径不能被一份坏计价快照打成 500：解析失败时只少了解释字段，金额照常给。 */
  @Test
  void brokenPriceSnapshotStillReturnsFrozenAmount() {
    OrderPo order = order(BigDecimal.valueOf(4000));
    when(orderMapper.selectById(1L)).thenReturn(order);
    OrderItemPo roomFee = item(1L, "ROOM_FEE", "包厢计时费", null,
        new BigDecimal("4000"), new BigDecimal("1"), new BigDecimal("4000"));
    roomFee.setPriceSnapshotJson("{ this is not json");
    when(orderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(roomFee));
    KtvSessionPo closed = closedSession();
    closed.setBillingRuleSnapshotJson("{ also broken");
    when(ktvSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(closed));
    when(serverSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

    BillResult bill = service.buildBill(1L);

    assertEquals(4000L, bill.roomFee().amount());
    assertNull(bill.roomFee().planName());
    assertEquals(0, bill.roomFee().incrementMinutes());
    assertEquals(4000L, bill.totalAmount());
  }

  private static KtvPricingPlan plan() {
    return new KtvPricingPlan(io.openware.platform.order.domain.ktv.model.KtvBillingUnit.HOUR, 3000L, 120, 0,
        new BigDecimal("1.5"), 30,
        io.openware.platform.order.domain.ktv.model.KtvRoundingDirection.CONSUMER_FAVOR, 5000L);
  }

  /** 一口价套餐：计费单位为 PACKAGE（不按时长递增）。 */
  private static KtvPricingPlan packagePlan() {
    return new KtvPricingPlan(io.openware.platform.order.domain.ktv.model.KtvBillingUnit.PACKAGE, 20000L, 240, 0,
        BigDecimal.ONE, 30,
        io.openware.platform.order.domain.ktv.model.KtvRoundingDirection.CONSUMER_FAVOR, 0L);
  }

  /** 演示 F11 报告里的实测场景：旧实现只列 ¥5（服务目录项被 SERVICE 过滤掉），合计 ¥15，明细对不上。 */
  @Test
  void serviceCatalogItemIsNoLongerDroppedFromItems() {
    OrderPo order = order(BigDecimal.valueOf(1500));
    when(orderMapper.selectById(1L)).thenReturn(order);
    when(orderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
        item(1L, "PRODUCT", "矿泉水", null, new BigDecimal("500"), new BigDecimal("1"), new BigDecimal("500")),
        item(2L, "SERVICE", "服务员点歌", null, new BigDecimal("1000"), new BigDecimal("1"), new BigDecimal("1000"))));
    when(ktvSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
    when(serverSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

    BillResult bill = service.buildBill(1L);

    assertEquals(2, bill.items().size());
    assertEquals(1500L, bill.items().stream().mapToLong(BillResult.ItemLine::amount).sum());
    assertEquals(bill.totalAmount(), bill.items().stream().mapToLong(BillResult.ItemLine::amount).sum());
    assertNull(bill.roomFee());
  }

  /** 未结束的服务人员点单不计入账单（ENDED 才计费）。 */
  @Test
  void unfinishedServerSessionIsNotBilled() {
    OrderPo order = order(BigDecimal.ZERO);
    when(orderMapper.selectById(1L)).thenReturn(order);
    when(orderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
    when(ktvSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
    KtvServerSessionPo ordered = serverSession();
    ordered.setStatus("SERVING");
    when(serverSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(ordered));

    BillResult bill = service.buildBill(1L);

    assertTrue(bill.servers().isEmpty());
    assertEquals(0L, bill.totalAmount());
  }

  /**
   * 开台中（未结台）的账单必须按「此刻结台」算出房费并计入合计：
   * 门店开台后马上看到的应收不能是 0（此前 OPEN 会话既没有 ROOM_FEE 明细、账单也不计算房费；
   * 存量开台订单同样靠这条口径立刻显示正确金额）。
   */
  @Test
  void billComputesLiveRoomFeeForOpenSession() {
    OrderPo order = order(BigDecimal.ZERO);
    order.setStatus("SERVING");
    order.setPaidAmount(BigDecimal.ZERO);
    when(orderMapper.selectById(1L)).thenReturn(order);
    // 存量开台订单：库里还没有房费明细（本轮修复前开的台），合计也是 0。
    when(orderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
    when(ktvSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(openSession()));
    when(serverSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
    when(pricingPlanProvider.resolve(any(), any())).thenReturn(
        new io.openware.platform.order.domain.ktv.model.KtvPricingPlan(
            io.openware.platform.order.domain.ktv.model.KtvBillingUnit.HOUR, 3000L, 120, 0,
            new BigDecimal("1.5"), 30,
            io.openware.platform.order.domain.ktv.model.KtvRoundingDirection.CONSUMER_FAVOR, 5000L));

    BillResult bill = service.buildBill(1L);

    assertNotNull(bill.roomFee(), "开台中也要有房费行，否则门店看到的应收是 0");
    assertTrue(bill.roomFee().amount() > 0, "开台 30 分钟至少一个计费块，金额必须 > 0");
    assertEquals(bill.roomFee().amount(), bill.totalAmount(), "合计必须包含实时房费（不能仍是 0）");
    assertEquals(bill.roomFee().amount(), bill.payableAmount(), "应收 = 实时合计 − 已收");
  }

  private static KtvSessionPo openSession() {
    KtvSessionPo po = new KtvSessionPo();
    po.setId(1L);
    po.setTenantId(100L);
    po.setOrderId(1L);
    po.setStatus("OPEN");
    po.setBillingStartAt(LocalDateTime.now().minusMinutes(30));
    po.setPausedSeconds(0);
    return po;
  }

  private static OrderPo order(BigDecimal totalAmount) {
    OrderPo po = new OrderPo();
    po.setId(1L);
    po.setTenantId(100L);
    po.setStoreId(100L);
    po.setStatus("COMPLETED");
    po.setCurrencyCode("CNY");
    po.setTotalAmount(totalAmount);
    po.setPaidAmount(totalAmount);
    po.setDiscountAmount(BigDecimal.ZERO);
    po.setTaxAmount(BigDecimal.ZERO);
    return po;
  }

  private static OrderItemPo item(Long id, String itemType, String name, Long resourceId,
                                  BigDecimal unitPrice, BigDecimal quantity, BigDecimal totalAmount) {
    OrderItemPo po = new OrderItemPo();
    po.setId(id);
    po.setTenantId(100L);
    po.setOrderId(1L);
    po.setItemType(itemType);
    po.setNameSnapshot(name);
    po.setResourceId(resourceId);
    po.setUnitPrice(unitPrice);
    po.setQuantity(quantity);
    po.setTotalAmount(totalAmount);
    po.setStatus("ACTIVE");
    return po;
  }

  private static KtvSessionPo closedSession() {
    KtvSessionPo po = new KtvSessionPo();
    po.setId(1L);
    po.setTenantId(100L);
    po.setOrderId(1L);
    po.setStatus("CLOSED");
    po.setBillingStartAt(LocalDateTime.now().minusMinutes(90));
    po.setClosedAt(LocalDateTime.now());
    po.setPausedSeconds(0);
    return po;
  }

  private static KtvServerSessionPo serverSession() {
    KtvServerSessionPo po = new KtvServerSessionPo();
    po.setId(1L);
    po.setTenantId(100L);
    po.setOrderId(1L);
    po.setKtvSessionId(20L);
    po.setServerResourceId(9L);
    po.setStatus("ENDED");
    po.setDurationSeconds(3600);
    po.setPricePerInc(5000L);
    po.setTotalAmount(BigDecimal.valueOf(10000L));
    return po;
  }

  private static KtvServerSessionPo serverSession(long id, long serverResourceId, BigDecimal amount) {
    KtvServerSessionPo po = serverSession();
    po.setId(id);
    po.setServerResourceId(serverResourceId);
    po.setTotalAmount(amount);
    return po;
  }

  // —— 包厢费已含 1 名标准服务人员：servers 分区按创建顺序标注免费名额，且不重不漏 ——

  /** 首名服务人员金额 0（含在包厢费里）也要出现在 servers 分区并标注，第 2 名按全价。 */
  @Test
  void freeServerQuotaIsShownAsZeroLineWithAnnotation() {
    // 合计 = 包厢费 22500 + 免费服务人员 0 + 额外服务人员 10000
    OrderPo order = order(BigDecimal.valueOf(32500));
    when(orderMapper.selectById(1L)).thenReturn(order);
    OrderItemPo freeItem = item(2L, "SERVICE", "服务人员#9（含 1 名标准服务人员，不另计费）", 9L,
        BigDecimal.ZERO, new BigDecimal("2"), BigDecimal.ZERO);
    freeItem.setPriceSnapshotJson("{\"serverPricePerInc\":5000,\"freeServerQuota\":true}");
    OrderItemPo extraItem = item(3L, "SERVICE", "额外服务人员#10", 10L,
        new BigDecimal("5000"), new BigDecimal("2"), new BigDecimal("10000"));
    extraItem.setPriceSnapshotJson("{\"serverPricePerInc\":5000,\"freeServerQuota\":false}");
    when(orderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
        item(1L, "ROOM_FEE", "包厢费（含 1 名服务人员）", null, new BigDecimal("7500"), new BigDecimal("3"), new BigDecimal("22500")),
        freeItem, extraItem));
    when(ktvSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(closedSession()));
    KtvServerSessionPo free = serverSession(1L, 9L, BigDecimal.ZERO);
    KtvServerSessionPo extra = serverSession(2L, 10L, BigDecimal.valueOf(10000L));
    when(serverSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(free, extra));

    BillResult bill = service.buildBill(1L);

    // servers 分区完整可解释：两条都在，免费那条金额 0 且标签说明原因
    assertEquals(2, bill.servers().size());
    assertEquals("服务人员#9（含 1 名标准服务人员，不另计费）", bill.servers().get(0).serverName());
    assertEquals(0L, bill.servers().get(0).amount());
    assertEquals("额外服务人员#10", bill.servers().get(1).serverName());
    assertEquals(10000L, bill.servers().get(1).amount());
    // 服务人员明细不进 items（避免重复计数）
    assertTrue(bill.items().isEmpty());

    // 不重不漏：sum(items) + sum(servers) + roomFee == totalAmount
    long itemsSum = bill.items().stream().mapToLong(BillResult.ItemLine::amount).sum();
    long serversSum = bill.servers().stream().mapToLong(BillResult.ServerLine::amount).sum();
    assertEquals(22500L, bill.roomFee().amount());
    assertEquals(bill.totalAmount(), itemsSum + serversSum + bill.roomFee().amount());
  }

  /** 未派服务人员：只有包厢费，servers 分区为空（包厢费仍按含 1 名服务人员的合计基数收）。 */
  @Test
  void noServerSessionKeepsRoomFeeOnly() {
    OrderPo order = order(BigDecimal.valueOf(22500));
    when(orderMapper.selectById(1L)).thenReturn(order);
    when(orderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
        item(1L, "ROOM_FEE", "包厢费（含 1 名服务人员）", null, new BigDecimal("7500"), new BigDecimal("3"), new BigDecimal("22500"))));
    when(ktvSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(closedSession()));
    when(serverSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

    BillResult bill = service.buildBill(1L);

    assertTrue(bill.servers().isEmpty());
    assertEquals(22500L, bill.roomFee().amount());
    assertEquals(bill.totalAmount(), bill.roomFee().amount());
  }

  /** 第 3 名起仍按「额外服务人员」标注，免费名额只有 1 个。 */
  @Test
  void onlyTheEarliestServerSessionIsFree() {
    OrderPo order = order(BigDecimal.valueOf(42500));
    when(orderMapper.selectById(1L)).thenReturn(order);
    when(orderItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
        item(1L, "ROOM_FEE", "包厢费（含 1 名服务人员）", null, new BigDecimal("7500"), new BigDecimal("3"), new BigDecimal("22500"))));
    when(ktvSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(closedSession()));
    when(serverSessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
        serverSession(1L, 9L, BigDecimal.ZERO),
        serverSession(2L, 10L, BigDecimal.valueOf(10000L)),
        serverSession(3L, 11L, BigDecimal.valueOf(10000L))));

    BillResult bill = service.buildBill(1L);

    assertEquals(3, bill.servers().size());
    assertTrue(bill.servers().get(0).serverName().contains("不另计费"));
    assertTrue(bill.servers().get(1).serverName().startsWith("额外服务人员#"));
    assertTrue(bill.servers().get(2).serverName().startsWith("额外服务人员#"));
  }
}

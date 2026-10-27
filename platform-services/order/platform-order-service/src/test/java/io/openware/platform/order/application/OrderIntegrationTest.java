package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.MqProducer;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.domain.ktv.model.KtvSession;
import io.openware.platform.order.domain.ktv.repository.KtvSessionRepository;
import io.openware.platform.order.infra.client.PaymentCollectedClient;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.infra.mq.EventOutboxRelay;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.infra.persistence.mapper.OrderItemMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.po.OrderItemPo;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 订单核心写路径集成测试（真跑）：
 * 开台(open RESERVED→OPEN, 订单 DRAFT→SERVING) → 结台(close OPEN→CLOSED,
 * 生成 ROOM_FEE 明细, 订单 SERVING→WAITING_SETTLEMENT)。
 * 使用 H2(MODE=MySQL) + 真实 Flyway(测试迁移) + 真实 MyBatis；仅 mock MQ/审计/资源域等外部依赖。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderIntegrationTest {

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

  /**
   * 资源域是外部依赖：开台占用登记改失败关闭后，未 mock 时会真的去打 platform-resource-service
   * 并因不可达而拒绝开台。这里显式放行占用登记，测试只验证订单/会话/计费链路。
   */
  @MockitoBean
  private ResourceStateClient resourceStateClient;

  /** payment 域是外部依赖：取消路径会用它确认「是否已有成功收款」，测试里按「无流水」返回。 */
  @MockitoBean
  private PaymentCollectedClient paymentCollectedClient;

  @Autowired
  private KtvSessionApplicationService ktvSessionApplicationService;

  @Autowired
  private OrderCancellationApplicationService orderCancellationApplicationService;

  @Autowired
  private SettlementApplicationService settlementApplicationService;

  @Autowired
  private KtvSessionRepository ktvSessionRepository;

  @Autowired
  private OrderMapper orderMapper;

  @Autowired
  private OrderItemMapper orderItemMapper;

  @BeforeEach
  void setUpTenant() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 0L, 0));
    when(resourceStateClient.occupy(any(), any(), any(), any(), any())).thenReturn(Optional.of(1L));
  }

  @AfterEach
  void tearDownTenant() {
    TenantContextHolder.clear();
  }

  @Test
  void openThenClose_writesRoomFeeItemAndAdvancesOrderStatus() {
    OrderPo order = newOrder();
    orderMapper.insert(order);

    KtvSession session = ktvSessionRepository.insert(newSession(order.getId()));

    // 开台：RESERVED → OPEN，订单 DRAFT → SERVING
    KtvSession opened = ktvSessionApplicationService.open(session.id(), null);
    assertEquals("OPEN", opened.status());
    assertNotNull(opened.openedAt());
    assertNotNull(opened.billingStartAt());
    assertNotNull(opened.billingRuleSnapshotJson());
    assertEquals("DRAFT", orderMapper.selectById(order.getId()).getTradeStatus());

    // 回拨计费起点到 90 分钟前，确保结台能产出正计时费（否则 billable≈0）。
    // 这是「整实体写回」（迁移前就是 updateById，不带 CAS），端口上的 update 逐字保留该语义。
    ktvSessionRepository.update(opened.withBillingStartAt(LocalDateTime.now().minusMinutes(90)));

    // 结台：OPEN → CLOSED，写入 ROOM_FEE 明细；订单 SERVING →（结台即结算）WAITING_PAYMENT
    KtvSession closed = ktvSessionApplicationService.close(session.id());
    assertEquals("CLOSED", closed.status());
    assertNotNull(closed.closedAt());
    assertEquals("PENDING_PAYMENT", orderMapper.selectById(order.getId()).getTradeStatus(),
        "结台即结算：没有待确认的客户自助加项时，结台后直接进入待收款");
    assertEquals("CNY", orderMapper.selectById(order.getId()).getCurrencyCode(),
        "历史单据的币种快照不得被开台/结台/金额写入改写");

    List<OrderItemPo> items =
        orderItemMapper.selectList(new QueryWrapper<OrderItemPo>().eq("order_id", order.getId()));
    assertEquals(1, items.size());
    OrderItemPo roomFee = items.get(0);
    assertEquals("ROOM_FEE", roomFee.getItemType());
    // 新口径：包厢费 = 房型单价 + 服务单价（已含 1 名标准服务人员）。
    // 默认方案 ¥100/小时（每块 5000）+ 服务 ¥50/小时（每块 2500）⇒ 每块 7500 分；
    // 90 分钟 = 3 块 = 22500 分（旧实现按整小时向上取整 = 2 单位 × 10000 = 20000 分）。
    assertEquals(0, roomFee.getTotalAmount().compareTo(new BigDecimal("22500")));
    assertEquals(0, roomFee.getQuantity().compareTo(new BigDecimal("3")));
    assertEquals(0, roomFee.getUnitPrice().compareTo(new BigDecimal("7500")));
    assertEquals("包厢费（含 1 名服务人员）", roomFee.getNameSnapshot());
    // 规则快照固化了实际使用的递增粒度与舍入方向（改规则不影响历史账单）。
    // H2 的 JSON 列回读时可能带转义引号，这里只断言字段名与取值存在。
    assertNotNull(roomFee.getPriceSnapshotJson());
    assertTrue(roomFee.getPriceSnapshotJson().contains("incrementMinutes"));
    assertTrue(roomFee.getPriceSnapshotJson().contains("30"));
    assertTrue(roomFee.getPriceSnapshotJson().contains("roundingDirection"));
    assertTrue(roomFee.getPriceSnapshotJson().contains("CONSUMER_FAVOR"));
    assertTrue(roomFee.getPriceSnapshotJson().contains("roomPricePerIncrement"));
    // 分项与合计、免费服务人员数一并固化，结台账单可完整复算
    assertTrue(roomFee.getPriceSnapshotJson().contains("serverUnitPrice"));
    assertTrue(roomFee.getPriceSnapshotJson().contains("combinedUnitPrice"));
    assertTrue(roomFee.getPriceSnapshotJson().contains("includedServerCount"));
  }

  /**
   * 运营取消订单（真跑）：订单交易状态 → CANCELLED（取消不是作废 VOIDED）+ cancelled_at，
   * 活动包厢会话 → CANCELLED，包厢占用被释放。
   * 这条用例覆盖「取消后包厢不再被占用」的端到端链路（占位登记由 mock 的 ResourceStateClient 承接）。
   */
  @Test
  void cancel_voidsOrderAndReleasesRoomSessionAndOccupation() {
    OrderPo order = newOrder();
    orderMapper.insert(order);
    KtvSession session = ktvSessionRepository.insert(newSession(order.getId()));
    // 开台：占用登记（mock 返回 occupationId=1）并把会话推进到 OPEN
    ktvSessionApplicationService.open(session.id(), null);
    assertEquals("DRAFT", orderMapper.selectById(order.getId()).getTradeStatus());

    Order cancelled = orderCancellationApplicationService.cancel(order.getId(), "客人要求取消");

    assertEquals("CANCELLED", cancelled.tradeStatus());
    assertNotNull(cancelled.cancelledAt());
    OrderPo cancelledRow = orderMapper.selectById(order.getId());
    assertEquals("CANCELLED", cancelledRow.getTradeStatus());
    KtvSession released = ktvSessionRepository.findById(session.id()).orElseThrow();
    assertEquals("CANCELLED", released.status());
    verify(resourceStateClient).release(1L);
  }

  /** 已有收款的订单不可取消：409 ORDER_HAS_PAYMENT_REFUND_FIRST，订单与会话都保持原状（不吞钱也不释放包厢）。 */
  @Test
  void cancel_rejectsCollectedOrderAndLeavesSessionUntouched() {
    OrderPo order = newOrder();
    order.setPaidAmount(new BigDecimal("100.000000"));
    orderMapper.insert(order);
    KtvSession session = ktvSessionRepository.insert(newSession(order.getId()));
    ktvSessionApplicationService.open(session.id(), null);

    ApiException ex = assertThrows(ApiException.class,
        () -> orderCancellationApplicationService.cancel(order.getId(), "客人要求取消"));

    assertEquals(409, ex.getStatus());
    assertEquals("ORDER_HAS_PAYMENT_REFUND_FIRST", ex.getCode());
    assertEquals("DRAFT", orderMapper.selectById(order.getId()).getTradeStatus());
    assertEquals("OPEN", ktvSessionRepository.findById(session.id()).orElseThrow().status());
  }

  /** 重复取消同一订单：幂等返回既有结果，不重复释放占用。 */
  @Test
  void cancel_isIdempotentOnSecondCall() {
    OrderPo order = newOrder();
    orderMapper.insert(order);
    KtvSession session = ktvSessionRepository.insert(newSession(order.getId()));
    ktvSessionApplicationService.open(session.id(), null);

    orderCancellationApplicationService.cancel(order.getId(), "客人要求取消");
    Order second = orderCancellationApplicationService.cancel(order.getId(), "客人要求取消");

    assertEquals("CANCELLED", second.tradeStatus());
    // 第二次不再释放占用：release 只发生一次（第一次取消）。
    verify(resourceStateClient).release(1L);
  }

  @Test
  void settle_crossTenant_throwsForbidden() {
    OrderPo order = newOrder();
    order.setTradeStatus("PENDING_PAYMENT");
    orderMapper.insert(order);

    TenantContextHolder.set(new TenantContext(2002L, 1L, STORE_ID, 0L, 0));
    try {
      ApiException ex = assertThrows(ApiException.class,
          () -> settlementApplicationService.settle(order.getId(), order.getVersion()));
      assertEquals(403, ex.getStatus());
      assertEquals("TENANT_SCOPE_DENIED", ex.getCode());
    } finally {
      TenantContextHolder.clear();
    }
  }

  @Test
  void selectTenantIdById_ignoresTenantLine_forCrossTenantDetection() {
    OrderPo order = newOrder();
    orderMapper.insert(order);

    TenantContextHolder.set(new TenantContext(2002L, 1L, STORE_ID, 0L, 0));
    try {
      assertNull(orderMapper.selectById(order.getId()));
      assertEquals(Long.valueOf(TENANT_ID), orderMapper.selectTenantIdById(order.getId()));
    } finally {
      TenantContextHolder.clear();
    }
  }

  private OrderPo newOrder() {
    OrderPo po = new OrderPo();
    po.setTenantId(TENANT_ID);
    po.setOrganizationId(1L);
    po.setStoreId(STORE_ID);
    po.setOrderNo("ORD-IT-" + System.nanoTime());
    po.setBusinessType("KTV");
    // 订单主表只有交易状态（兼容列 status 已随收口迁移删除）
    po.setTradeStatus("DRAFT");
    po.setRefundStatus("NONE");
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
    return po;
  }

  /**
   * 预留会话夹具：领域模型不可变，直接构造（字段与迁移前的持久化对象逐列对应）。
   * 预留到店/离店时间必须真的写进去——房费「标准时长」基准用它，缺了会改变结台金额。
   */
  private KtvSession newSession(Long orderId) {
    LocalDateTime now = LocalDateTime.now();
    return new KtvSession(null, TENANT_ID, null, "KTV", orderId, 3001L, null, null, null, null, null, null,
        now.minusMinutes(30), now.plusMinutes(120), null, null, "HOUR", null, 0, 0, null,
        new BigDecimal("1.0"), null, KtvSession.STATUS_RESERVED, 0);
  }
}

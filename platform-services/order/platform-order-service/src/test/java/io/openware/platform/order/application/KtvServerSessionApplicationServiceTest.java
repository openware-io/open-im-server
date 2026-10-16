package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.order.domain.ktv.model.KtvBillingUnit;
import io.openware.platform.order.domain.ktv.model.KtvPricingPlan;
import io.openware.platform.order.domain.ktv.model.KtvRoundingDirection;
import io.openware.platform.order.domain.ktv.model.KtvServerSessionStatus;
import io.openware.platform.order.domain.ktv.port.KtvPricingPlanProvider;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.domain.ktv.repository.KtvServerSessionRepository;
import io.openware.platform.order.domain.ktv.repository.KtvSessionRepository;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderItemMapper;
import io.openware.platform.order.domain.ktv.model.KtvServerSession;
import io.openware.platform.order.infra.persistence.po.OrderItemPo;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** KTV 服务人员点单状态机：ORDERED → SERVING → ENDED / CANCELLED（合法与非法转换）。 */
class KtvServerSessionApplicationServiceTest {

  private final KtvServerSessionRepository sessionRepository = mock(KtvServerSessionRepository.class);
  private final OrderMapper orderMapper = mock(OrderMapper.class);
  private final KtvPricingPlanProvider pricingPlanProvider = mock(KtvPricingPlanProvider.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
  private final KtvSessionRepository ktvSessionRepository = mock(KtvSessionRepository.class);
  private final KtvServerSessionApplicationService service =
      new KtvServerSessionApplicationService(sessionRepository, orderMapper, pricingPlanProvider, auditClient, orderItemMapper);

  /**
   * 订单金额写入走乐观锁提交（受影响 1 行 = 库中版本仍是期望值）。
   *
   * <p>Mockito 对未 stub 的 int 返回 0，等于"CAS 失败"，而失败会被转成 ORDER_VERSION_CONFLICT——
   * 所以涉及订单金额重算的用例都以 CAS 成功为前提，在这里统一给出。
   */
  @BeforeEach
  void stubOrderCasSuccess() {
    when(orderMapper.updateAmountsAndStatusWithVersion(any(), anyInt())).thenReturn(1);
    // 点单落库：真实适配器会把生成的主键回填进返回的聚合（记录不可变 → 返回新实例）。
    // 桩返回入参本身，与迁移前「insert 就地回填 id」的语义等价，用例里的引用仍是同一条点单。
    when(sessionRepository.insert(any(KtvServerSession.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void order_createsOrderedSessionWithZeroAmount() {
    OrderPo order = new OrderPo();
    order.setId(10L);
    order.setStatus("SERVING");
    order.setStoreId(100L);
    when(orderMapper.selectById(10L)).thenReturn(order);
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());

    KtvServerSession po = service.order(1L, 10L, 20L, 30L, 40L);

    assertEquals(KtvServerSessionStatus.ORDERED.name(), po.status());
    assertEquals(0, po.durationSeconds());
    assertEquals(0L, po.totalAmount().longValueExact());
    assertEquals(0, po.version());
    verify(sessionRepository).insert(po);
  }

  @Test
  void order_rejectsWhenOrderIsNotServing() {
    OrderPo order = new OrderPo();
    order.setId(10L);
    order.setStatus("PAID");
    when(orderMapper.selectById(10L)).thenReturn(order);

    BusinessException ex = assertThrows(BusinessException.class,
        () -> service.order(1L, 10L, 20L, 30L, 40L));

    assertEquals("ORDER_STATUS_INVALID", ex.getCode());
  }

  @Test
  void start_transitionsOrderedToServing() {
    KtvServerSession po = session(KtvServerSessionStatus.ORDERED);
    when(sessionRepository.findById(po.id())).thenReturn(Optional.of(po));
    when(sessionRepository.updateWithVersion(any())).thenReturn(true);

    KtvServerSession result = service.start(po.id());

    assertEquals(KtvServerSessionStatus.SERVING.name(), result.status());
    assertNotNull(result.startedAt());
    // 记录不可变：服务提交 CAS 的是「改过状态的新实例」，原断言的同一引用语义已不成立；
    // 改为捕获实参并断言「同一条点单 + 推进后的状态」，比原来更强。
    capturedCas(po.id(), KtvServerSessionStatus.SERVING);
  }

  @Test
  void start_rejectsNonOrderedSession() {
    when(sessionRepository.findById(1L)).thenReturn(Optional.of(session(KtvServerSessionStatus.SERVING)));

    BusinessException ex = assertThrows(BusinessException.class, () -> service.start(1L));

    assertEquals("ORDER_STATUS_INVALID", ex.getCode());
  }

  @Test
  void end_transitionsServingToEnded() {
    KtvServerSession po = session(KtvServerSessionStatus.SERVING);
    po = po.withStartedAt(LocalDateTime.now().minusMinutes(31));
    when(sessionRepository.findById(po.id())).thenReturn(Optional.of(po));
    when(sessionRepository.updateWithVersion(any())).thenReturn(true);

    KtvServerSession result = service.end(po.id());

    assertEquals(KtvServerSessionStatus.ENDED.name(), result.status());
    assertNotNull(result.endedAt());
    assertNotNull(result.totalAmount());
    capturedCas(po.id(), KtvServerSessionStatus.ENDED);
  }

  @Test
  void end_transitionsOrderedToEndedUsingOrderedAtAsStart() {
    KtvServerSession po = session(KtvServerSessionStatus.ORDERED);
    when(sessionRepository.findById(po.id())).thenReturn(Optional.of(po));
    when(sessionRepository.updateWithVersion(any())).thenReturn(true);

    KtvServerSession result = service.end(po.id());

    assertEquals(KtvServerSessionStatus.ENDED.name(), result.status());
    assertNotNull(result.endedAt());
    capturedCas(po.id(), KtvServerSessionStatus.ENDED);
  }

  @Test
  void end_rejectsAlreadyEndedSession() {
    when(sessionRepository.findById(1L)).thenReturn(Optional.of(session(KtvServerSessionStatus.ENDED)));

    BusinessException ex = assertThrows(BusinessException.class, () -> service.end(1L));

    assertEquals("ORDER_STATUS_INVALID", ex.getCode());
  }

  @Test
  void cancel_transitionsOrderedToCancelled() {
    KtvServerSession po = session(KtvServerSessionStatus.ORDERED);
    when(sessionRepository.findById(po.id())).thenReturn(Optional.of(po));
    when(sessionRepository.updateWithVersion(any())).thenReturn(true);

    KtvServerSession result = service.cancel(po.id());

    assertEquals(KtvServerSessionStatus.CANCELLED.name(), result.status());
    capturedCas(po.id(), KtvServerSessionStatus.CANCELLED);
  }

  @Test
  void cancel_rejectsNonOrderedSession() {
    when(sessionRepository.findById(1L)).thenReturn(Optional.of(session(KtvServerSessionStatus.SERVING)));

    BusinessException ex = assertThrows(BusinessException.class, () -> service.cancel(1L));

    assertEquals("ORDER_STATUS_INVALID", ex.getCode());
  }

  @Test
  void require_throwsWhenSessionMissing() {
    when(sessionRepository.findById(any())).thenReturn(Optional.empty());
    when(sessionRepository.findTenantIdById(any())).thenReturn(null);

    BusinessException ex = assertThrows(BusinessException.class, () -> service.start(1L));

    assertEquals("ORDER_NOT_FOUND", ex.getCode());
  }

  @Test
  void require_throwsForbiddenWhenCrossTenant() {
    when(sessionRepository.findById(any())).thenReturn(Optional.empty());
    when(sessionRepository.findTenantIdById(any())).thenReturn(99L);

    ApiException ex = assertThrows(ApiException.class, () -> service.start(1L));

    assertEquals(403, ex.getStatus());
    assertEquals("TENANT_SCOPE_DENIED", ex.getCode());
  }

  /** 点服务人员：把服务人员快照（ID + 资源名）写入 KTV 会话，订单列表/房态看板直接读会话。 */
  @Test
  void order_writesServerSnapshotIntoKtvSession() {
    OrderPo order = new OrderPo();
    order.setId(10L);
    order.setStatus("SERVING");
    order.setStoreId(100L);
    when(orderMapper.selectById(10L)).thenReturn(order);
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());
    ResourceStateClient resourceClient = mock(ResourceStateClient.class);
    when(resourceClient.room(30L)).thenReturn(java.util.Optional.of(
        new ResourceStateClient.RoomSnapshot("小美", "S001", null, null, null, null, null)));
    KtvServerSessionApplicationService withResource = new KtvServerSessionApplicationService(
        sessionRepository, orderMapper, pricingPlanProvider, auditClient, orderItemMapper, ktvSessionRepository, resourceClient);

    withResource.order(1L, 10L, 20L, 30L, 40L);

    verify(ktvSessionRepository).updateServerSnapshot(eq(20L), eq(30L), eq("小美"));
  }

  /** 开始服务时再固化一次（点单可能未带 ktvSessionId）；资源不可达时只写 ID，名称交给 SQL 保留原值。 */
  @Test
  void start_rewritesServerSnapshotWithNullNameWhenResourceUnavailable() {
    // 会话上带包厢会话与服务人员快照（记录不可变，显式构造）。
    KtvServerSession po = fixture(1L, 10L, 20L, 30L, KtvServerSessionStatus.ORDERED, null);
    when(sessionRepository.findById(po.id())).thenReturn(Optional.of(po));
    when(sessionRepository.updateWithVersion(any())).thenReturn(true);
    ResourceStateClient resourceClient = mock(ResourceStateClient.class);
    when(resourceClient.room(30L)).thenReturn(java.util.Optional.empty());
    KtvServerSessionApplicationService withResource = new KtvServerSessionApplicationService(
        sessionRepository, orderMapper, pricingPlanProvider, auditClient, orderItemMapper, ktvSessionRepository, resourceClient);

    withResource.start(po.id());

    verify(ktvSessionRepository).updateServerSnapshot(eq(20L), eq(30L), org.mockito.ArgumentMatchers.isNull());
  }

  /** 缺少 ktvSessionId（老调用）时跳过会话回写，不能因为多一个可选依赖阻断点单。 */
  @Test
  void order_withoutKtvSessionIdSkipsSessionSnapshot() {
    OrderPo order = new OrderPo();
    order.setId(10L);
    order.setStatus("SERVING");
    order.setStoreId(100L);
    when(orderMapper.selectById(10L)).thenReturn(order);
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());
    KtvServerSessionApplicationService withResource = new KtvServerSessionApplicationService(
        sessionRepository, orderMapper, pricingPlanProvider, auditClient, orderItemMapper, ktvSessionRepository, null);

    withResource.order(1L, 10L, null, 30L, 40L);

    verify(ktvSessionRepository, org.mockito.Mockito.never())
        .updateServerSnapshot(any(), any(), any());
  }

  /**
   * 点单夹具：领域模型不可变，逐列对应迁移前的持久化对象（id=1、订单 10、5 分钟前点单，
   * 递增粒度 30、单价 5000、让利舍入、version 0）。
   */
  private static KtvServerSession session(KtvServerSessionStatus status) {
    return fixture(1L, 10L, null, null, status, null);
  }

  /** 指定 id / 状态的同包厢会话（免费名额按创建顺序 = id 升序判定，测试需要多条）。 */
  private static KtvServerSession serverSession(long id, KtvServerSessionStatus status, Long ktvSessionId) {
    return fixture(id, 10L, ktvSessionId, id + 100, status, plan().toSnapshotJson());
  }

  /** 显式构造：会话标识/归属/包厢会话/服务人员/状态/计价快照由调用方给定，其余与旧夹具一致。 */
  private static KtvServerSession fixture(long id, Long orderId, Long ktvSessionId, Long serverResourceId,
      KtvServerSessionStatus status, String priceSnapshotJson) {
    return new KtvServerSession(id, 1L, null, null, orderId, ktvSessionId, serverResourceId, null,
        LocalDateTime.now().minusMinutes(5), null, null, null, 30,
        KtvRoundingDirection.CONSUMER_FAVOR.name(), 5000L, null, null, null, null, priceSnapshotJson,
        status.name(), 0);
  }

  /**
   * CAS 提交的实参断言：服务提交给端口的是「改过状态的新实例」（记录不可变），
   * 因此不能再按同一引用断言；这里钉住「同一条点单 + 推进后的状态」，语义比原来更强。
   */
  private KtvServerSession capturedCas(long expectedId, KtvServerSessionStatus expectedStatus) {
    ArgumentCaptor<KtvServerSession> captor = ArgumentCaptor.forClass(KtvServerSession.class);
    verify(sessionRepository).updateWithVersion(captor.capture());
    assertEquals(expectedId, captor.getValue().id(), "CAS 必须落在同一条点单上");
    assertEquals(expectedStatus.name(), captor.getValue().status(), "CAS 必须是推进后的状态");
    return captor.getValue();
  }

  // —— 免费服务人员名额：包厢费已含 1 名标准服务人员，第 2 名起另计（KTV_BUSINESS_01 §5）——

  /** 唯一一名服务人员占用免费名额：金额 0，但明细照写并标注，servers 分区不能凭空少一个人。 */
  @Test
  void end_marksEarliestServerSessionAsFreeQuota() {
    KtvServerSession po = fixture(1L, 10L, null, null, KtvServerSessionStatus.SERVING,
        plan().toSnapshotJson()).withStartedAt(LocalDateTime.now().minusMinutes(31));
    when(sessionRepository.findById(po.id())).thenReturn(Optional.of(po));
    when(sessionRepository.listByOrderId(10L)).thenReturn(List.of(po));
    when(sessionRepository.updateWithVersion(any())).thenReturn(true);

    KtvServerSession result = service.end(po.id());

    assertEquals(0L, result.totalAmount().longValueExact(), "首名服务人员已含在包厢费里，不另计费");
    ArgumentCaptor<OrderItemPo> captor = ArgumentCaptor.forClass(OrderItemPo.class);
    verify(orderItemMapper).insert(captor.capture());
    OrderItemPo item = captor.getValue();
    assertEquals(0L, item.getUnitPrice().longValueExact());
    assertEquals(2, item.getQuantity().intValueExact(), "免费也要记录计费块数，时长可解释");
    assertEquals(0L, item.getTotalAmount().longValueExact());
    assertTrue(item.getNameSnapshot().contains("不另计费"), item.getNameSnapshot());
    assertTrue(item.getPriceSnapshotJson().contains("\"freeServerQuota\":true"), item.getPriceSnapshotJson());
  }

  /** 第 2 名起按各自会话单价全价计（31 分钟 → 2 块 × 5000 = 10000）。 */
  @Test
  void end_chargesSecondServerSessionInFull() {
    KtvServerSession first = serverSession(1L, KtvServerSessionStatus.SERVING, null);
    first = first.withStartedAt(LocalDateTime.now().minusMinutes(31));
    KtvServerSession second = serverSession(2L, KtvServerSessionStatus.SERVING, null);
    second = second.withStartedAt(LocalDateTime.now().minusMinutes(31));
    when(sessionRepository.findById(2L)).thenReturn(Optional.of(second));
    when(sessionRepository.listByOrderId(10L)).thenReturn(List.of(first, second));
    when(sessionRepository.updateWithVersion(any())).thenReturn(true);

    KtvServerSession result = service.end(2L);

    assertEquals(10000L, result.totalAmount().longValueExact());
    ArgumentCaptor<OrderItemPo> captor = ArgumentCaptor.forClass(OrderItemPo.class);
    verify(orderItemMapper).insert(captor.capture());
    OrderItemPo item = captor.getValue();
    assertEquals(5000L, item.getUnitPrice().longValueExact(), "第 2 名起单价不再抹零");
    assertEquals(10000L, item.getTotalAmount().longValueExact());
    assertTrue(item.getNameSnapshot().startsWith("额外服务人员#"), item.getNameSnapshot());
    assertTrue(item.getPriceSnapshotJson().contains("\"freeServerQuota\":false"), item.getPriceSnapshotJson());
  }

  /** 第 3 名同样全价（免费名额只有 1 个）。 */
  @Test
  void end_chargesThirdServerSessionInFull() {
    KtvServerSession first = serverSession(1L, KtvServerSessionStatus.ENDED, null);
    first = first.withStartedAt(LocalDateTime.now().minusMinutes(31));
    KtvServerSession second = serverSession(2L, KtvServerSessionStatus.ENDED, null);
    second = second.withStartedAt(LocalDateTime.now().minusMinutes(31));
    KtvServerSession third = serverSession(3L, KtvServerSessionStatus.SERVING, null);
    third = third.withStartedAt(LocalDateTime.now().minusMinutes(31));
    when(sessionRepository.findById(3L)).thenReturn(Optional.of(third));
    when(sessionRepository.listByOrderId(10L)).thenReturn(List.of(first, second, third));
    when(sessionRepository.updateWithVersion(any())).thenReturn(true);

    assertEquals(10000L, service.end(3L).totalAmount().longValueExact());
  }

  /** 名额按创建顺序判定：最早的会话被取消时顺延给下一个未取消会话，绝不出现两个免费。 */
  @Test
  void end_handsFreeQuotaToNextWhenEarliestIsCancelled() {
    KtvServerSession cancelled = serverSession(1L, KtvServerSessionStatus.CANCELLED, null);
    KtvServerSession second = serverSession(2L, KtvServerSessionStatus.SERVING, null);
    second = second.withStartedAt(LocalDateTime.now().minusMinutes(31));
    KtvServerSession third = serverSession(3L, KtvServerSessionStatus.SERVING, null);
    third = third.withStartedAt(LocalDateTime.now().minusMinutes(31));
    when(sessionRepository.findById(2L)).thenReturn(Optional.of(second));
    when(sessionRepository.listByOrderId(10L)).thenReturn(List.of(cancelled, second, third));
    when(sessionRepository.updateWithVersion(any())).thenReturn(true);

    assertEquals(0L, service.end(2L).totalAmount().longValueExact(), "名额顺延给下一个未取消会话");
  }

  /** 乱序 end：第 2 名先结束也照价收费，免费名额仍留给创建最早的会话（幂等、可复算）。 */
  @Test
  void freeQuotaFollowsCreationOrderNotEndOrder() {
    KtvServerSession first = serverSession(1L, KtvServerSessionStatus.SERVING, null);
    first = first.withStartedAt(LocalDateTime.now().minusMinutes(31));
    KtvServerSession second = serverSession(2L, KtvServerSessionStatus.SERVING, null);
    second = second.withStartedAt(LocalDateTime.now().minusMinutes(31));
    when(sessionRepository.findById(2L)).thenReturn(Optional.of(second));
    when(sessionRepository.listByOrderId(10L)).thenReturn(List.of(first, second));
    when(sessionRepository.updateWithVersion(any())).thenReturn(true);

    assertEquals(10000L, service.end(2L).totalAmount().longValueExact(),
        "创建顺序在后的服务人员先结束，不因乱序免单");
  }

  /** 免费名额按包厢会话分组：不同包厢会话各有一个名额，同一会话永远只有一个。 */
  @Test
  void freeServerSessionIds_grantsOneQuotaPerRoomSession() {
    KtvServerSession a1 = serverSession(1L, KtvServerSessionStatus.ENDED, 20L);
    KtvServerSession a2 = serverSession(2L, KtvServerSessionStatus.ENDED, 20L);
    KtvServerSession a3 = serverSession(3L, KtvServerSessionStatus.ENDED, 20L);
    KtvServerSession b1 = serverSession(4L, KtvServerSessionStatus.ENDED, 21L);

    assertEquals(java.util.Set.of(1L, 4L),
        KtvServerSessionApplicationService.freeServerSessionIds(List.of(a1, a2, a3, b1)));
  }

  /** 查不到同类会话（数据缺失）时按不免费处理：宁可收费也不静默漏收。 */
  @Test
  void end_chargesWhenPeerSessionsUnavailable() {
    KtvServerSession po = session(KtvServerSessionStatus.SERVING);
    po = po.withStartedAt(LocalDateTime.now().minusMinutes(31));
    when(sessionRepository.findById(po.id())).thenReturn(Optional.of(po));
    when(sessionRepository.updateWithVersion(any())).thenReturn(true);
    // 未 stub selectByOrderId：Mockito 返回空集合，无法确认名额归属 → 全价

    assertEquals(10000L, service.end(po.id()).totalAmount().longValueExact());
  }

  /**
   * 服务人员费写入明细后**必须同步重算订单金额快照**：否则库内 {@code ord_order.total_amount}
   * 不含这笔服务费，账单会出现「服务人员行有钱、合计对不上」（sum(items)+sum(servers)+roomFee ≠ total），
   * 并且要等到下一次结算/房费刷新才补上——中间一直少收。
   */
  @Test
  void endRecalculatesOrderAmountsAfterWritingServerFeeItem() {
    KtvServerSession free = serverSession(1L, KtvServerSessionStatus.SERVING, null);
    free = free.withStartedAt(LocalDateTime.now().minusMinutes(31));
    KtvServerSession charged = serverSession(2L, KtvServerSessionStatus.SERVING, null);
    charged = charged.withStartedAt(LocalDateTime.now().minusMinutes(31));
    when(sessionRepository.findById(2L)).thenReturn(Optional.of(charged));
    when(sessionRepository.listByOrderId(10L)).thenReturn(List.of(free, charged));
    when(sessionRepository.updateWithVersion(any())).thenReturn(true);
    OrderPo order = new OrderPo();
    order.setId(10L);
    order.setTenantId(1L);
    order.setStoreId(100L);
    order.setStatus("SERVING");
    order.setCurrencyCode("CNY");
    order.setPaidAmount(BigDecimal.ZERO);
    when(orderMapper.selectById(10L)).thenReturn(order);
    // 真库行为：结台/重算读的是已落库的 ACTIVE 明细，这里回放刚写入的那条服务人员费。
    OrderItemPo stored = new OrderItemPo();
    stored.setId(99L);
    stored.setOrderId(10L);
    stored.setItemType("SERVICE");
    stored.setStatus("ACTIVE");
    stored.setUnitPrice(new BigDecimal("5000"));
    stored.setQuantity(new BigDecimal("2"));
    stored.setTotalAmount(new BigDecimal("10000"));
    when(orderItemMapper.selectList(any())).thenReturn(List.of(stored));
    KtvServerSessionApplicationService withAmounts = new KtvServerSessionApplicationService(
        sessionRepository, orderMapper, pricingPlanProvider, auditClient, orderItemMapper, ktvSessionRepository, null,
        new OrderAmountApplicationService(orderMapper, orderItemMapper));

    withAmounts.end(2L);

    assertEquals(0, new BigDecimal("10000").compareTo(order.getTotalAmount()),
        "订单合计必须立刻包含服务人员费");
    verify(orderMapper).updateAmountsAndStatusWithVersion(eq(order), anyInt());
  }

  private static KtvPricingPlan plan() {
    return new KtvPricingPlan(KtvBillingUnit.HOUR, 3000L, 120, 0, new BigDecimal("1.5"), 30,
        KtvRoundingDirection.CONSUMER_FAVOR, 5000L);
  }

  // ---------------------------------------------------------------------------------------------
  // 领域留痕：点单/结束服务/取消点单三处此前成功与失败都没有留痕（/business/** 无 BFF 兜底）。
  // ---------------------------------------------------------------------------------------------

  @Test
  void order_writesSucceededAudit() {
    OrderPo order = new OrderPo();
    order.setId(10L);
    order.setStatus("SERVING");
    order.setStoreId(100L);
    order.setCurrencyCode("CNY");
    when(orderMapper.selectById(10L)).thenReturn(order);
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());

    service.order(1L, 10L, 20L, 30L, 40L);

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_server_session.order", record.action());
    assertEquals("点服务人员", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    assertEquals("ktv_server_session", record.resourceType());
  }

  @Test
  void order_writesFailureAuditWithStableErrorCode() {
    OrderPo order = new OrderPo();
    order.setId(10L);
    order.setStatus("PAID");
    when(orderMapper.selectById(10L)).thenReturn(order);

    BusinessException ex = assertThrows(BusinessException.class, () -> service.order(1L, 10L, 20L, 30L, 40L));

    assertEquals("ORDER_STATUS_INVALID", ex.getCode());
    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_server_session.order", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("ORDER_STATUS_INVALID", record.errorCode());
    assertNull(record.idempotencyKey());
  }

  @Test
  void end_writesSucceededAudit() {
    KtvServerSession po = session(KtvServerSessionStatus.SERVING);
    po = po.withStartedAt(LocalDateTime.now().minusMinutes(31));
    when(sessionRepository.findById(po.id())).thenReturn(Optional.of(po));
    when(sessionRepository.listByOrderId(10L)).thenReturn(List.of(po));
    when(sessionRepository.updateWithVersion(any())).thenReturn(true);

    service.end(po.id());

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_server_session.end", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
  }

  @Test
  void end_writesFailureAuditWithStableErrorCode() {
    when(sessionRepository.findById(1L)).thenReturn(Optional.of(session(KtvServerSessionStatus.CANCELLED)));

    BusinessException ex = assertThrows(BusinessException.class, () -> service.end(1L));

    assertEquals("ORDER_STATUS_INVALID", ex.getCode());
    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_server_session.end", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("ORDER_STATUS_INVALID", record.errorCode());
  }

  @Test
  void cancel_writesSucceededAudit() {
    KtvServerSession po = session(KtvServerSessionStatus.ORDERED);
    when(sessionRepository.findById(po.id())).thenReturn(Optional.of(po));
    when(sessionRepository.updateWithVersion(any())).thenReturn(true);

    service.cancel(po.id());

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_server_session.cancel", record.action());
    assertEquals("服务人员点单取消", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
  }

  @Test
  void cancel_writesFailureAuditWithStableErrorCode() {
    when(sessionRepository.findById(1L)).thenReturn(Optional.of(session(KtvServerSessionStatus.SERVING)));

    BusinessException ex = assertThrows(BusinessException.class, () -> service.cancel(1L));

    assertEquals("ORDER_STATUS_INVALID", ex.getCode());
    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_server_session.cancel", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("ORDER_STATUS_INVALID", record.errorCode());
  }

  private AuditClient.AuditRecord capturedAudit() {
    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    return captor.getValue();
  }
}

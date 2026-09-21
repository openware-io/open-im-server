package com.gvchat.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.platform.order.domain.ktv.model.KtvBillingUnit;
import com.gvchat.platform.order.domain.ktv.model.KtvPricingPlan;
import com.gvchat.platform.order.domain.ktv.model.KtvRoundingDirection;
import com.gvchat.platform.order.domain.ktv.model.KtvServerSessionStatus;
import com.gvchat.platform.order.domain.ktv.port.KtvPricingPlanProvider;
import com.gvchat.platform.order.infra.client.ResourceStateClient;
import com.gvchat.platform.order.infra.persistence.mapper.KtvServerSessionMapper;
import com.gvchat.platform.order.infra.persistence.mapper.KtvSessionMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderItemMapper;
import com.gvchat.platform.order.infra.persistence.po.KtvServerSessionPo;
import com.gvchat.platform.order.infra.persistence.po.OrderItemPo;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** KTV 服务人员点单状态机：ORDERED → SERVING → ENDED / CANCELLED（合法与非法转换）。 */
class KtvServerSessionApplicationServiceTest {

  private final KtvServerSessionMapper sessionMapper = mock(KtvServerSessionMapper.class);
  private final OrderMapper orderMapper = mock(OrderMapper.class);
  private final KtvPricingPlanProvider pricingPlanProvider = mock(KtvPricingPlanProvider.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
  private final KtvSessionMapper ktvSessionMapper = mock(KtvSessionMapper.class);
  private final KtvServerSessionApplicationService service =
      new KtvServerSessionApplicationService(sessionMapper, orderMapper, pricingPlanProvider, auditClient, orderItemMapper);

  @Test
  void order_createsOrderedSessionWithZeroAmount() {
    OrderPo order = new OrderPo();
    order.setId(10L);
    order.setStatus("SERVING");
    order.setStoreId(100L);
    when(orderMapper.selectById(10L)).thenReturn(order);
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());

    KtvServerSessionPo po = service.order(1L, 10L, 20L, 30L, 40L);

    assertEquals(KtvServerSessionStatus.ORDERED.name(), po.getStatus());
    assertEquals(0, po.getDurationSeconds());
    assertEquals(0L, po.getTotalAmount().longValueExact());
    assertEquals(0, po.getVersion());
    verify(sessionMapper).insert(po);
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
    KtvServerSessionPo po = session(KtvServerSessionStatus.ORDERED);
    when(sessionMapper.selectById(po.getId())).thenReturn(po);
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    KtvServerSessionPo result = service.start(po.getId());

    assertEquals(KtvServerSessionStatus.SERVING.name(), result.getStatus());
    assertNotNull(result.getStartedAt());
    verify(sessionMapper).updateWithVersion(po);
  }

  @Test
  void start_rejectsNonOrderedSession() {
    when(sessionMapper.selectById(1L)).thenReturn(session(KtvServerSessionStatus.SERVING));

    BusinessException ex = assertThrows(BusinessException.class, () -> service.start(1L));

    assertEquals("ORDER_STATUS_INVALID", ex.getCode());
  }

  @Test
  void end_transitionsServingToEnded() {
    KtvServerSessionPo po = session(KtvServerSessionStatus.SERVING);
    po.setStartedAt(LocalDateTime.now().minusMinutes(31));
    when(sessionMapper.selectById(po.getId())).thenReturn(po);
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    KtvServerSessionPo result = service.end(po.getId());

    assertEquals(KtvServerSessionStatus.ENDED.name(), result.getStatus());
    assertNotNull(result.getEndedAt());
    assertNotNull(result.getTotalAmount());
    verify(sessionMapper).updateWithVersion(po);
  }

  @Test
  void end_transitionsOrderedToEndedUsingOrderedAtAsStart() {
    KtvServerSessionPo po = session(KtvServerSessionStatus.ORDERED);
    when(sessionMapper.selectById(po.getId())).thenReturn(po);
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    KtvServerSessionPo result = service.end(po.getId());

    assertEquals(KtvServerSessionStatus.ENDED.name(), result.getStatus());
    assertNotNull(result.getEndedAt());
    verify(sessionMapper).updateWithVersion(po);
  }

  @Test
  void end_rejectsAlreadyEndedSession() {
    when(sessionMapper.selectById(1L)).thenReturn(session(KtvServerSessionStatus.ENDED));

    BusinessException ex = assertThrows(BusinessException.class, () -> service.end(1L));

    assertEquals("ORDER_STATUS_INVALID", ex.getCode());
  }

  @Test
  void cancel_transitionsOrderedToCancelled() {
    KtvServerSessionPo po = session(KtvServerSessionStatus.ORDERED);
    when(sessionMapper.selectById(po.getId())).thenReturn(po);
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    KtvServerSessionPo result = service.cancel(po.getId());

    assertEquals(KtvServerSessionStatus.CANCELLED.name(), result.getStatus());
    verify(sessionMapper).updateWithVersion(po);
  }

  @Test
  void cancel_rejectsNonOrderedSession() {
    when(sessionMapper.selectById(1L)).thenReturn(session(KtvServerSessionStatus.SERVING));

    BusinessException ex = assertThrows(BusinessException.class, () -> service.cancel(1L));

    assertEquals("ORDER_STATUS_INVALID", ex.getCode());
  }

  @Test
  void require_throwsWhenSessionMissing() {
    when(sessionMapper.selectById(any())).thenReturn(null);
    when(sessionMapper.selectTenantIdById(any())).thenReturn(null);

    BusinessException ex = assertThrows(BusinessException.class, () -> service.start(1L));

    assertEquals("ORDER_NOT_FOUND", ex.getCode());
  }

  @Test
  void require_throwsForbiddenWhenCrossTenant() {
    when(sessionMapper.selectById(any())).thenReturn(null);
    when(sessionMapper.selectTenantIdById(any())).thenReturn(99L);

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
        sessionMapper, orderMapper, pricingPlanProvider, auditClient, orderItemMapper, ktvSessionMapper, resourceClient);

    withResource.order(1L, 10L, 20L, 30L, 40L);

    verify(ktvSessionMapper).updateServerSnapshot(eq(20L), eq(30L), eq("小美"), any(LocalDateTime.class));
  }

  /** 开始服务时再固化一次（点单可能未带 ktvSessionId）；资源不可达时只写 ID，名称交给 SQL 保留原值。 */
  @Test
  void start_rewritesServerSnapshotWithNullNameWhenResourceUnavailable() {
    KtvServerSessionPo po = session(KtvServerSessionStatus.ORDERED);
    po.setKtvSessionId(20L);
    po.setServerResourceId(30L);
    when(sessionMapper.selectById(po.getId())).thenReturn(po);
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);
    ResourceStateClient resourceClient = mock(ResourceStateClient.class);
    when(resourceClient.room(30L)).thenReturn(java.util.Optional.empty());
    KtvServerSessionApplicationService withResource = new KtvServerSessionApplicationService(
        sessionMapper, orderMapper, pricingPlanProvider, auditClient, orderItemMapper, ktvSessionMapper, resourceClient);

    withResource.start(po.getId());

    verify(ktvSessionMapper).updateServerSnapshot(eq(20L), eq(30L), org.mockito.ArgumentMatchers.isNull(),
        any(LocalDateTime.class));
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
        sessionMapper, orderMapper, pricingPlanProvider, auditClient, orderItemMapper, ktvSessionMapper, null);

    withResource.order(1L, 10L, null, 30L, 40L);

    verify(ktvSessionMapper, org.mockito.Mockito.never())
        .updateServerSnapshot(any(), any(), any(), any());
  }

  private static KtvServerSessionPo session(KtvServerSessionStatus status) {
    KtvServerSessionPo po = new KtvServerSessionPo();
    po.setId(1L);
    po.setTenantId(1L);
    po.setOrderId(10L);
    po.setStatus(status.name());
    po.setOrderedAt(LocalDateTime.now().minusMinutes(5));
    po.setRoundingDirection(KtvRoundingDirection.CONSUMER_FAVOR.name());
    po.setIncrementMinutes(30);
    po.setPricePerInc(5000L);
    po.setVersion(0);
    return po;
  }

  /** 指定 id / 状态的同包厢会话（免费名额按创建顺序 = id 升序判定，测试需要多条）。 */
  private static KtvServerSessionPo serverSession(long id, KtvServerSessionStatus status, Long ktvSessionId) {
    KtvServerSessionPo po = session(status);
    po.setId(id);
    po.setServerResourceId(id + 100);
    po.setKtvSessionId(ktvSessionId);
    po.setPriceSnapshotJson(plan().toSnapshotJson());
    return po;
  }

  // —— 免费服务人员名额：包厢费已含 1 名标准服务人员，第 2 名起另计（KTV_BUSINESS_01 §5）——

  /** 唯一一名服务人员占用免费名额：金额 0，但明细照写并标注，servers 分区不能凭空少一个人。 */
  @Test
  void end_marksEarliestServerSessionAsFreeQuota() {
    KtvServerSessionPo po = session(KtvServerSessionStatus.SERVING);
    po.setStartedAt(LocalDateTime.now().minusMinutes(31));
    po.setPriceSnapshotJson(plan().toSnapshotJson());
    when(sessionMapper.selectById(po.getId())).thenReturn(po);
    when(sessionMapper.selectByOrderId(10L)).thenReturn(List.of(po));
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    KtvServerSessionPo result = service.end(po.getId());

    assertEquals(0L, result.getTotalAmount().longValueExact(), "首名服务人员已含在包厢费里，不另计费");
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
    KtvServerSessionPo first = serverSession(1L, KtvServerSessionStatus.SERVING, null);
    first.setStartedAt(LocalDateTime.now().minusMinutes(31));
    KtvServerSessionPo second = serverSession(2L, KtvServerSessionStatus.SERVING, null);
    second.setStartedAt(LocalDateTime.now().minusMinutes(31));
    when(sessionMapper.selectById(2L)).thenReturn(second);
    when(sessionMapper.selectByOrderId(10L)).thenReturn(List.of(first, second));
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    KtvServerSessionPo result = service.end(2L);

    assertEquals(10000L, result.getTotalAmount().longValueExact());
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
    KtvServerSessionPo first = serverSession(1L, KtvServerSessionStatus.ENDED, null);
    first.setStartedAt(LocalDateTime.now().minusMinutes(31));
    KtvServerSessionPo second = serverSession(2L, KtvServerSessionStatus.ENDED, null);
    second.setStartedAt(LocalDateTime.now().minusMinutes(31));
    KtvServerSessionPo third = serverSession(3L, KtvServerSessionStatus.SERVING, null);
    third.setStartedAt(LocalDateTime.now().minusMinutes(31));
    when(sessionMapper.selectById(3L)).thenReturn(third);
    when(sessionMapper.selectByOrderId(10L)).thenReturn(List.of(first, second, third));
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    assertEquals(10000L, service.end(3L).getTotalAmount().longValueExact());
  }

  /** 名额按创建顺序判定：最早的会话被取消时顺延给下一个未取消会话，绝不出现两个免费。 */
  @Test
  void end_handsFreeQuotaToNextWhenEarliestIsCancelled() {
    KtvServerSessionPo cancelled = serverSession(1L, KtvServerSessionStatus.CANCELLED, null);
    KtvServerSessionPo second = serverSession(2L, KtvServerSessionStatus.SERVING, null);
    second.setStartedAt(LocalDateTime.now().minusMinutes(31));
    KtvServerSessionPo third = serverSession(3L, KtvServerSessionStatus.SERVING, null);
    third.setStartedAt(LocalDateTime.now().minusMinutes(31));
    when(sessionMapper.selectById(2L)).thenReturn(second);
    when(sessionMapper.selectByOrderId(10L)).thenReturn(List.of(cancelled, second, third));
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    assertEquals(0L, service.end(2L).getTotalAmount().longValueExact(), "名额顺延给下一个未取消会话");
  }

  /** 乱序 end：第 2 名先结束也照价收费，免费名额仍留给创建最早的会话（幂等、可复算）。 */
  @Test
  void freeQuotaFollowsCreationOrderNotEndOrder() {
    KtvServerSessionPo first = serverSession(1L, KtvServerSessionStatus.SERVING, null);
    first.setStartedAt(LocalDateTime.now().minusMinutes(31));
    KtvServerSessionPo second = serverSession(2L, KtvServerSessionStatus.SERVING, null);
    second.setStartedAt(LocalDateTime.now().minusMinutes(31));
    when(sessionMapper.selectById(2L)).thenReturn(second);
    when(sessionMapper.selectByOrderId(10L)).thenReturn(List.of(first, second));
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    assertEquals(10000L, service.end(2L).getTotalAmount().longValueExact(),
        "创建顺序在后的服务人员先结束，不因乱序免单");
  }

  /** 免费名额按包厢会话分组：不同包厢会话各有一个名额，同一会话永远只有一个。 */
  @Test
  void freeServerSessionIds_grantsOneQuotaPerRoomSession() {
    KtvServerSessionPo a1 = serverSession(1L, KtvServerSessionStatus.ENDED, 20L);
    KtvServerSessionPo a2 = serverSession(2L, KtvServerSessionStatus.ENDED, 20L);
    KtvServerSessionPo a3 = serverSession(3L, KtvServerSessionStatus.ENDED, 20L);
    KtvServerSessionPo b1 = serverSession(4L, KtvServerSessionStatus.ENDED, 21L);

    assertEquals(java.util.Set.of(1L, 4L),
        KtvServerSessionApplicationService.freeServerSessionIds(List.of(a1, a2, a3, b1)));
  }

  /** 查不到同类会话（数据缺失）时按不免费处理：宁可收费也不静默漏收。 */
  @Test
  void end_chargesWhenPeerSessionsUnavailable() {
    KtvServerSessionPo po = session(KtvServerSessionStatus.SERVING);
    po.setStartedAt(LocalDateTime.now().minusMinutes(31));
    when(sessionMapper.selectById(po.getId())).thenReturn(po);
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);
    // 未 stub selectByOrderId：Mockito 返回空集合，无法确认名额归属 → 全价

    assertEquals(10000L, service.end(po.getId()).getTotalAmount().longValueExact());
  }

  /**
   * 服务人员费写入明细后**必须同步重算订单金额快照**：否则库内 {@code ord_order.total_amount}
   * 不含这笔服务费，账单会出现「服务人员行有钱、合计对不上」（sum(items)+sum(servers)+roomFee ≠ total），
   * 并且要等到下一次结算/房费刷新才补上——中间一直少收。
   */
  @Test
  void endRecalculatesOrderAmountsAfterWritingServerFeeItem() {
    KtvServerSessionPo free = serverSession(1L, KtvServerSessionStatus.SERVING, null);
    free.setStartedAt(LocalDateTime.now().minusMinutes(31));
    KtvServerSessionPo charged = serverSession(2L, KtvServerSessionStatus.SERVING, null);
    charged.setStartedAt(LocalDateTime.now().minusMinutes(31));
    when(sessionMapper.selectById(2L)).thenReturn(charged);
    when(sessionMapper.selectByOrderId(10L)).thenReturn(List.of(free, charged));
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);
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
        sessionMapper, orderMapper, pricingPlanProvider, auditClient, orderItemMapper, ktvSessionMapper, null,
        new OrderAmountApplicationService(orderMapper, orderItemMapper));

    withAmounts.end(2L);

    assertEquals(0, new BigDecimal("10000").compareTo(order.getTotalAmount()),
        "订单合计必须立刻包含服务人员费");
    verify(orderMapper).updateById(order);
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
    KtvServerSessionPo po = session(KtvServerSessionStatus.SERVING);
    po.setStartedAt(LocalDateTime.now().minusMinutes(31));
    when(sessionMapper.selectById(po.getId())).thenReturn(po);
    when(sessionMapper.selectByOrderId(10L)).thenReturn(List.of(po));
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    service.end(po.getId());

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_server_session.end", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
  }

  @Test
  void end_writesFailureAuditWithStableErrorCode() {
    when(sessionMapper.selectById(1L)).thenReturn(session(KtvServerSessionStatus.CANCELLED));

    BusinessException ex = assertThrows(BusinessException.class, () -> service.end(1L));

    assertEquals("ORDER_STATUS_INVALID", ex.getCode());
    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_server_session.end", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("ORDER_STATUS_INVALID", record.errorCode());
  }

  @Test
  void cancel_writesSucceededAudit() {
    KtvServerSessionPo po = session(KtvServerSessionStatus.ORDERED);
    when(sessionMapper.selectById(po.getId())).thenReturn(po);
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    service.cancel(po.getId());

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_server_session.cancel", record.action());
    assertEquals("服务人员点单取消", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
  }

  @Test
  void cancel_writesFailureAuditWithStableErrorCode() {
    when(sessionMapper.selectById(1L)).thenReturn(session(KtvServerSessionStatus.SERVING));

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

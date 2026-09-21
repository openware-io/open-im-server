package com.gvchat.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.platform.order.domain.ktv.model.KtvBillingUnit;
import com.gvchat.platform.order.domain.ktv.model.KtvPricingPlan;
import com.gvchat.platform.order.domain.ktv.model.KtvRoundingDirection;
import com.gvchat.platform.order.domain.ktv.port.KtvPricingPlanProvider;
import com.gvchat.platform.order.infra.client.ResourceStateClient;
import com.gvchat.platform.order.infra.persistence.mapper.KtvSessionMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderMapper;
import com.gvchat.platform.order.infra.persistence.po.KtvSessionPo;
import com.gvchat.platform.order.infra.persistence.po.OrderItemPo;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** KTV 包厢会话状态机：RESERVED → OPEN → PAUSED(可选) → CLOSED（合法与非法转换 + 结台计费）。 */
class KtvSessionApplicationServiceTest {

  private final KtvSessionMapper sessionMapper = mock(KtvSessionMapper.class);
  private final OrderMapper orderMapper = mock(OrderMapper.class);
  private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
  private final KtvPricingPlanProvider pricingPlanProvider = mock(KtvPricingPlanProvider.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final OrderAmountApplicationService orderAmounts = new OrderAmountApplicationService(orderMapper, orderItemMapper);
  private final KtvSessionApplicationService service = new KtvSessionApplicationService(
      sessionMapper, orderMapper, orderItemMapper, pricingPlanProvider, auditClient, orderAmounts);

  @Test
  void open_transitionsReservedToOpenAndServingOrder() {
    KtvSessionPo session = session("RESERVED");
    OrderPo order = order("DRAFT");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order);
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    KtvSessionPo result = service.open(session.getId(), 0);

    assertEquals("OPEN", result.getStatus());
    assertNotNull(result.getOpenedAt());
    assertEquals("HOUR", result.getBillingUnit());
    assertEquals(0, result.getFreeWaitMinutes());
    assertNotNull(result.getBillingStartAt());
    assertNotNull(result.getBillingRuleSnapshotJson());
    assertEquals("SERVING", order.getStatus());
    verify(sessionMapper).updateWithVersion(session);
    verify(orderMapper).updateById(order);
  }

  @Test
  void open_rejectsNonReservedSession() {
    when(sessionMapper.selectById(1L)).thenReturn(session("OPEN"));

    BusinessException ex = assertThrows(BusinessException.class, () -> service.open(1L, 0));

    assertEquals("ORDER_STATUS_INVALID", ex.getCode());
    verify(sessionMapper, never()).updateWithVersion(any(KtvSessionPo.class));
  }

  @Test
  void pause_transitionsOpenToPaused() {
    KtvSessionPo session = session("OPEN");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    KtvSessionPo result = service.pause(session.getId());

    assertEquals("PAUSED", result.getStatus());
    verify(sessionMapper).updateWithVersion(session);
  }

  @Test
  void pause_rejectsNonOpenSession() {
    when(sessionMapper.selectById(1L)).thenReturn(session("RESERVED"));

    BusinessException ex = assertThrows(BusinessException.class, () -> service.pause(1L));

    assertEquals("ORDER_STATUS_INVALID", ex.getCode());
  }

  @Test
  void resume_transitionsPausedToOpen() {
    KtvSessionPo session = session("PAUSED");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    KtvSessionPo result = service.resume(session.getId());

    assertEquals("OPEN", result.getStatus());
    verify(sessionMapper).updateWithVersion(session);
  }

  @Test
  void resume_rejectsNonPausedSession() {
    when(sessionMapper.selectById(1L)).thenReturn(session("OPEN"));

    BusinessException ex = assertThrows(BusinessException.class, () -> service.resume(1L));

    assertEquals("ORDER_STATUS_INVALID", ex.getCode());
  }

  /**
   * 结台计费口径（F7）：房费按 increment_minutes 递增 + CONSUMER_FAVOR。
   * 方案 3000 分/小时 + 30 分钟递增 ⇒ 每块 1500 分；30 分钟 = 1 块 = 1500 分（旧实现按整小时收 3000 分）。
   */
  @Test
  void close_transitionsToClosedAndWritesRoomFeeItem() {
    KtvSessionPo session = session("OPEN");
    session.setBillingStartAt(LocalDateTime.now().minusMinutes(30));
    OrderPo order = order("SERVING");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order);
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    KtvSessionPo result = service.close(session.getId());

    assertEquals("CLOSED", result.getStatus());
    assertNotNull(result.getClosedAt());
    assertEquals("WAITING_SETTLEMENT", order.getStatus());
    verify(sessionMapper).updateWithVersion(session);

    ArgumentCaptor<OrderItemPo> captor = ArgumentCaptor.forClass(OrderItemPo.class);
    verify(orderItemMapper).insert(captor.capture());
    OrderItemPo item = captor.getValue();
    assertEquals("ROOM_FEE", item.getItemType());
    assertEquals(1L, item.getTenantId());
    assertEquals(session.getOrderId(), item.getOrderId());
    assertEquals(0, new BigDecimal("1500").compareTo(item.getTotalAmount()));
    assertEquals(0, new BigDecimal("1500").compareTo(item.getUnitPrice()));
    assertEquals(0, new BigDecimal("1").compareTo(item.getQuantity()));
    // 快照必须固化实际使用的规则字段：递增粒度 + 舍入方向 + 每递增粒度单价。
    assertNotNull(item.getPriceSnapshotJson());
    assertTrue(item.getPriceSnapshotJson().contains("\"incrementMinutes\":30"));
    assertTrue(item.getPriceSnapshotJson().contains("\"roundingDirection\":\"CONSUMER_FAVOR\""));
    assertTrue(item.getPriceSnapshotJson().contains("\"roomPricePerIncrement\":1500"));
  }

  /** 边界：开台 33 秒结台，让利口径下房费 0 元（而不是旧实现的「1 小时 3000 分」）。 */
  @Test
  void close_chargesNothingForSubMinuteSession() {
    KtvSessionPo session = session("OPEN");
    session.setBillingStartAt(LocalDateTime.now().minusSeconds(33));
    OrderPo order = order("SERVING");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order);
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    service.close(session.getId());

    ArgumentCaptor<OrderItemPo> captor = ArgumentCaptor.forClass(OrderItemPo.class);
    verify(orderItemMapper).insert(captor.capture());
    assertEquals(0, BigDecimal.ZERO.compareTo(captor.getValue().getTotalAmount()));
    assertEquals(0, BigDecimal.ZERO.compareTo(captor.getValue().getQuantity()));
  }

  /** 跨多块进位：方案 3000 分/小时 + 30 分钟递增，95 分钟 → 4 块 = 6000 分。 */
  @Test
  void close_crossesIntoNextIncrementBlock() {
    KtvSessionPo session = session("OPEN");
    session.setBillingStartAt(LocalDateTime.now().minusMinutes(95).minusSeconds(5));
    OrderPo order = order("SERVING");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order);
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    service.close(session.getId());

    ArgumentCaptor<OrderItemPo> captor = ArgumentCaptor.forClass(OrderItemPo.class);
    verify(orderItemMapper).insert(captor.capture());
    assertEquals(0, new BigDecimal("6000").compareTo(captor.getValue().getTotalAmount()));
    assertEquals(0, new BigDecimal("4").compareTo(captor.getValue().getQuantity()));
  }

  /**
   * F7 关键约束：结台落库与开台中实时预估必须是同一算法（同一份计算结果），
   * 否则页面显示的预估金额与结台账单会打架。
   */
  @Test
  void liveEstimateMatchesCloseAmount() {
    KtvSessionPo session = session("OPEN");
    session.setBillingStartAt(LocalDateTime.now().minusMinutes(40).minusSeconds(5));
    session.setBillingRuleSnapshotJson(plan().toSnapshotJson());
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order("SERVING"));
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    service.fillLiveEstimate(session, 100L);
    long estimated = session.getEstimatedRoomFee();
    // 40 分钟 → M=40 → n=ceil(40/30)=2 块 × 1500 = 3000
    assertEquals(3000L, estimated);

    service.close(session.getId());
    ArgumentCaptor<OrderItemPo> captor = ArgumentCaptor.forClass(OrderItemPo.class);
    verify(orderItemMapper).insert(captor.capture());
    assertEquals(estimated, captor.getValue().getTotalAmount().longValueExact(),
        "实时预估金额必须等于结台 ROOM_FEE 明细金额");
  }

  @Test
  void close_rejectsWhenAlreadyClosed() {
    when(sessionMapper.selectById(1L)).thenReturn(session("CLOSED"));

    BusinessException ex = assertThrows(BusinessException.class, () -> service.close(1L));

    assertEquals("ORDER_STATUS_INVALID", ex.getCode());
    verify(orderItemMapper, never()).insert(any(OrderItemPo.class));
  }

  @Test
  void open_throwsWhenSessionMissing() {
    when(sessionMapper.selectById(any())).thenReturn(null);
    when(sessionMapper.selectTenantIdById(any())).thenReturn(null);

    BusinessException ex = assertThrows(BusinessException.class, () -> service.open(1L, 0));

    assertEquals("ORDER_NOT_FOUND", ex.getCode());
  }

  @Test
  void open_throwsForbiddenWhenCrossTenant() {
    when(sessionMapper.selectById(any())).thenReturn(null);
    when(sessionMapper.selectTenantIdById(any())).thenReturn(99L);

    ApiException ex = assertThrows(ApiException.class, () -> service.open(1L, 0));

    assertEquals(403, ex.getStatus());
    assertEquals("TENANT_SCOPE_DENIED", ex.getCode());
  }

  @Test
  void create_insertsReservedSession() {
    when(sessionMapper.insert(any(KtvSessionPo.class))).thenAnswer(inv -> {
      KtvSessionPo po = inv.getArgument(0);
      po.setId(42L);
      return 1;
    });

    KtvSessionPo result = service.create(1L, 10L, 3001L);

    assertEquals("RESERVED", result.getStatus());
    assertEquals(1L, result.getTenantId());
    assertEquals(10L, result.getOrderId());
    assertEquals(3001L, result.getRoomResourceId());
    assertEquals(42L, result.getId());
  }

  @Test
  void cancel_transitionsReservedToCancelled() {
    KtvSessionPo session = session("RESERVED");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    KtvSessionPo result = service.cancel(session.getId());

    assertEquals("CANCELLED", result.getStatus());
    verify(sessionMapper).updateWithVersion(session);
  }

  /**
   * 订单取消（cancelByOrder）：OPEN 会话 → CANCELLED，且占用按 IN_USE 语义 **release**。
   * 不释放会让包厢被门禁永久占住（订单已取消、房态仍「使用中」）。
   */
  @Test
  void cancelByOrder_cancelsOpenSessionAndReleasesOccupation() {
    KtvSessionPo session = session("OPEN");
    session.setOccupationId(77L);
    session.setRoomResourceId(3001L);
    when(sessionMapper.selectByOrderId(session.getOrderId())).thenReturn(session);
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);
    ResourceStateClient resourceClient = mock(ResourceStateClient.class);
    KtvSessionApplicationService withResource = new KtvSessionApplicationService(sessionMapper, orderMapper,
        orderItemMapper, pricingPlanProvider, auditClient, orderAmounts, resourceClient);

    KtvSessionPo cancelled = withResource.cancelByOrder(session.getOrderId());

    assertEquals("CANCELLED", cancelled.getStatus());
    verify(sessionMapper).updateWithVersion(session);
    verify(resourceClient).release(77L);
    verify(resourceClient, never()).cancel(any());
  }

  /** RESERVED（未开台）会话的占用还是预占：用 cancel 语义释放，避免把未开始的占用标成已用。 */
  @Test
  void cancelByOrder_cancelsReservedSessionWithCancelSemantics() {
    KtvSessionPo session = session("RESERVED");
    session.setOccupationId(88L);
    when(sessionMapper.selectByOrderId(session.getOrderId())).thenReturn(session);
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);
    ResourceStateClient resourceClient = mock(ResourceStateClient.class);
    KtvSessionApplicationService withResource = new KtvSessionApplicationService(sessionMapper, orderMapper,
        orderItemMapper, pricingPlanProvider, auditClient, orderAmounts, resourceClient);

    KtvSessionPo cancelled = withResource.cancelByOrder(session.getOrderId());

    assertEquals("CANCELLED", cancelled.getStatus());
    verify(resourceClient).cancel(88L);
    verify(resourceClient, never()).release(any());
  }

  /** 已结台/已取消的会话视为已终结：不重复释放、不重复留痕（订单取消因此天然幂等）。 */
  @Test
  void cancelByOrder_isNoOpForTerminalSession() {
    when(sessionMapper.selectByOrderId(10L)).thenReturn(session("CLOSED"));
    ResourceStateClient resourceClient = mock(ResourceStateClient.class);
    KtvSessionApplicationService withResource = new KtvSessionApplicationService(sessionMapper, orderMapper,
        orderItemMapper, pricingPlanProvider, auditClient, orderAmounts, resourceClient);

    assertNull(withResource.cancelByOrder(10L));

    verify(sessionMapper, never()).updateWithVersion(any());
    verifyNoInteractions(resourceClient, auditClient);
  }

  /** 订单没有包厢会话（如纯商品订单）：返回 null，不抛异常。 */
  @Test
  void cancelByOrder_isNoOpWithoutSession() {
    when(sessionMapper.selectByOrderId(10L)).thenReturn(null);

    assertNull(service.cancelByOrder(10L));

    verify(sessionMapper, never()).updateWithVersion(any());
    verifyNoInteractions(auditClient);
  }

  /** 开台登记人数：写入会话（订单列表/房态看板据此展示「人数」）。 */
  @Test
  void open_recordsPartySize() {
    KtvSessionPo session = session("RESERVED");
    OrderPo order = order("DRAFT");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order);
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    KtvSessionPo result = service.open(session.getId(), 0, 8);

    assertEquals(8, result.getPartySize());
    verify(sessionMapper).updateWithVersion(session);
  }

  /** 人数超过包厢容量：400，且不得登记占用、不得推进会话（否则会留下「没开台却占用」的幽灵记录）。 */
  @Test
  void open_rejectsPartySizeAboveRoomCapacityBeforeOccupying() {
    KtvSessionPo session = session("RESERVED");
    session.setRoomResourceId(3001L);
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order("DRAFT"));
    ResourceStateClient resourceClient = mock(ResourceStateClient.class);
    when(resourceClient.room(3001L)).thenReturn(java.util.Optional.of(roomSnapshot(12)));
    KtvSessionApplicationService withResource = new KtvSessionApplicationService(sessionMapper, orderMapper,
        orderItemMapper, pricingPlanProvider, auditClient, orderAmounts, resourceClient);

    ApiException ex = assertThrows(ApiException.class, () -> withResource.open(session.getId(), 0, 13));

    assertEquals(400, ex.getStatus());
    assertEquals("PARTY_SIZE_INVALID", ex.getCode());
    verify(resourceClient, never()).occupy(any(), any(), any(), any(), any());
    verify(sessionMapper, never()).updateWithVersion(any(KtvSessionPo.class));
  }

  @Test
  void open_rejectsNonPositivePartySize() {
    KtvSessionPo session = session("RESERVED");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order("DRAFT"));

    ApiException ex = assertThrows(ApiException.class, () -> service.open(session.getId(), 0, 0));

    assertEquals(400, ex.getStatus());
    assertEquals("PARTY_SIZE_INVALID", ex.getCode());
    verify(sessionMapper, never()).updateWithVersion(any(KtvSessionPo.class));
  }

  /**
   * 清洁中的包厢不能开台：清洁中**没有任何占用记录**，只靠 occupy 拦不住，
   * /ktv/sessions/{id}/open 与「预约开台」都能把正在打扫的包厢开出去。
   */
  @Test
  void open_rejectsCleaningRoomBeforeOccupying() {
    KtvSessionPo session = session("RESERVED");
    session.setRoomResourceId(3001L);
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order("DRAFT"));
    ResourceStateClient resourceClient = mock(ResourceStateClient.class);
    when(resourceClient.room(3001L)).thenReturn(java.util.Optional.of(roomSnapshot(12)));
    when(resourceClient.requireState(3001L)).thenReturn(
        new ResourceStateClient.RoomState(false, "CLEANING", "清洁中", "VIP 01"));
    KtvSessionApplicationService withResource = new KtvSessionApplicationService(sessionMapper, orderMapper,
        orderItemMapper, pricingPlanProvider, auditClient, orderAmounts, resourceClient);

    ApiException ex = assertThrows(ApiException.class, () -> withResource.open(session.getId(), 0, 4));

    assertEquals(409, ex.getStatus());
    assertEquals("ROOM_UNAVAILABLE", ex.getCode());
    assertTrue(ex.getMessage().contains("清洁中"), ex.getMessage());
    verify(resourceClient, never()).occupy(any(), any(), any(), any(), any());
    verify(sessionMapper, never()).updateWithVersion(any(KtvSessionPo.class));
  }

  /** 已被其它会话占用的包厢同样拒绝开台（占用门禁与占用法冲突判定同一口径）。 */
  @Test
  void open_rejectsOccupiedRoomBeforeOccupying() {
    KtvSessionPo session = session("RESERVED");
    session.setRoomResourceId(3001L);
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order("DRAFT"));
    ResourceStateClient resourceClient = mock(ResourceStateClient.class);
    when(resourceClient.room(3001L)).thenReturn(java.util.Optional.of(roomSnapshot(12)));
    when(resourceClient.requireState(3001L)).thenReturn(
        new ResourceStateClient.RoomState(false, "OCCUPIED", "使用中", "VIP 01"));
    KtvSessionApplicationService withResource = new KtvSessionApplicationService(sessionMapper, orderMapper,
        orderItemMapper, pricingPlanProvider, auditClient, orderAmounts, resourceClient);

    ApiException ex = assertThrows(ApiException.class, () -> withResource.open(session.getId(), 0, 4));

    assertEquals("ROOM_UNAVAILABLE", ex.getCode());
    verify(resourceClient, never()).occupy(any(), any(), any(), any(), any());
  }

  /** 房态服务不可达：fail-closed（不降级开台），否则会出现「订单开台了、房态不知道」的重复开台。 */
  @Test
  void open_failsClosedWhenRoomStateUnavailable() {
    KtvSessionPo session = session("RESERVED");
    session.setRoomResourceId(3001L);
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order("DRAFT"));
    ResourceStateClient resourceClient = mock(ResourceStateClient.class);
    when(resourceClient.room(3001L)).thenReturn(java.util.Optional.of(roomSnapshot(12)));
    when(resourceClient.requireState(3001L))
        .thenThrow(new BusinessException("RESOURCE_STATE_UNAVAILABLE", "房态服务暂时不可用"));
    KtvSessionApplicationService withResource = new KtvSessionApplicationService(sessionMapper, orderMapper,
        orderItemMapper, pricingPlanProvider, auditClient, orderAmounts, resourceClient);

    BusinessException ex = assertThrows(BusinessException.class, () -> withResource.open(session.getId(), 0, 4));

    assertEquals("RESOURCE_STATE_UNAVAILABLE", ex.getCode());
    verify(resourceClient, never()).occupy(any(), any(), any(), any(), any());
  }

  /**
   * 按房型定价：资源带房型单价时开台即按房型价计费，并把「实际使用的房型与单价」固化进规则快照；
   * 结台写出的 ROOM_FEE 明细快照必须保留同一房型口径（金额也按房型价算）。
   */
  @Test
  void open_appliesRoomTypePriceAndFreezesItInSnapshots() {
    KtvSessionPo session = session("RESERVED");
    session.setRoomResourceId(3001L);
    OrderPo order = order("SERVING");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order);
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);
    ResourceStateClient resourceClient = mock(ResourceStateClient.class);
    // 房型 VIP 房费 20000 分/小时、服务人员 6000 分/小时；包厢容量 12
    when(resourceClient.room(3001L)).thenReturn(java.util.Optional.of(roomSnapshot(12)));
    when(resourceClient.requireState(3001L)).thenReturn(
        new ResourceStateClient.RoomState(true, "IDLE", null, "VIP 01"));
    when(resourceClient.occupy(eq(3001L), eq("ORDER"), eq(10L), any(), any()))
        .thenReturn(java.util.Optional.of(99L));
    KtvSessionApplicationService withResource = new KtvSessionApplicationService(sessionMapper, orderMapper,
        orderItemMapper, pricingPlanProvider, auditClient, orderAmounts, resourceClient);

    KtvSessionPo opened = withResource.open(session.getId(), 0, 6);

    assertEquals(99L, opened.getOccupationId());
    assertEquals("VIP 01", opened.getRoomNameSnapshot());
    assertEquals("V01", opened.getRoomCodeSnapshot());
    String snapshot = opened.getBillingRuleSnapshotJson();
    assertTrue(snapshot.contains("\"roomTypeCode\":\"VIP\""), snapshot);
    assertTrue(snapshot.contains("\"roomTypeName\":\"VIP 大包\""), snapshot);
    assertTrue(snapshot.contains("\"roomUnitPrice\":20000"), snapshot);
    assertTrue(snapshot.contains("\"roomTypePriceApplied\":true"), snapshot);
    // 20000 分/小时 + 30 分钟递增 → 每块 10000 分
    assertTrue(snapshot.contains("\"roomPricePerIncrement\":10000"), snapshot);
    // 房型服务人员单价 6000/小时 + 30 分钟递增 → 每块 3000 分
    assertTrue(snapshot.contains("\"serverPricePerInc\":3000"), snapshot);
    // 展示口径分项与合计也一并固化：C 端「包厢价格 = 房型 200.00 + 服务 60.00 = 260.00 元/小时」
    assertTrue(snapshot.contains("\"serverUnitPrice\":6000"), snapshot);
    assertTrue(snapshot.contains("\"combinedUnitPrice\":26000"), snapshot);

    // 开台即计费：ROOM_FEE 明细在**开台**时就落库（金额与「此刻结台」同一口径）。
    ArgumentCaptor<OrderItemPo> openCaptor = ArgumentCaptor.forClass(OrderItemPo.class);
    verify(orderItemMapper, times(1)).insert(openCaptor.capture());
    OrderItemPo openedItem = openCaptor.getValue();
    assertEquals("ROOM_FEE", openedItem.getItemType());
    // 真库行为：结台时能查到这行 → 走「更新同一行」；mock 的 selectList 缺省返回空列表，
    // 这里显式返回开台写入的那行，断言「结台不新增第二行」（多行会把房费重复计入合计）。
    when(orderItemMapper.selectList(any())).thenReturn(java.util.List.of(openedItem));

    // 结台：30 分钟 → 1 块 → 10000 分（房型价），明细快照保留房型与生效单价
    session.setBillingStartAt(LocalDateTime.now().minusMinutes(30));
    withResource.close(session.getId());

    verify(orderItemMapper, times(1)).insert(any(OrderItemPo.class));
    ArgumentCaptor<OrderItemPo> captor = ArgumentCaptor.forClass(OrderItemPo.class);
    verify(orderItemMapper).updateById(captor.capture());
    OrderItemPo item = captor.getValue();
    assertEquals(0, new BigDecimal("10000").compareTo(item.getTotalAmount()));
    assertEquals(0, new BigDecimal("10000").compareTo(item.getUnitPrice()));
    assertTrue(item.getPriceSnapshotJson().contains("\"roomTypeCode\":\"VIP\""), item.getPriceSnapshotJson());
    assertTrue(item.getPriceSnapshotJson().contains("\"roomUnitPrice\":20000"), item.getPriceSnapshotJson());
    // 房费金额仍只按房型单价计（10000 分），服务分项只是随快照固化，不并入房费
    assertTrue(item.getPriceSnapshotJson().contains("\"serverUnitPrice\":6000"), item.getPriceSnapshotJson());
    assertTrue(item.getPriceSnapshotJson().contains("\"combinedUnitPrice\":26000"), item.getPriceSnapshotJson());
  }

  /** 房型未定价：开台回退门店级单价，但快照仍记录用的是哪个房型（账单可解释）。 */
  @Test
  void open_fallsBackToStorePriceWhenRoomTypeIsNotPriced() {
    KtvSessionPo session = session("RESERVED");
    session.setRoomResourceId(3001L);
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order("DRAFT"));
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);
    ResourceStateClient resourceClient = mock(ResourceStateClient.class);
    when(resourceClient.room(3001L)).thenReturn(java.util.Optional.of(
        new ResourceStateClient.RoomSnapshot("VIP 01", "V01", 12, "SMALL", "小包", null, null)));
    when(resourceClient.requireState(3001L)).thenReturn(
        new ResourceStateClient.RoomState(true, "IDLE", null, "VIP 01"));
    when(resourceClient.occupy(any(), any(), any(), any(), any())).thenReturn(java.util.Optional.of(99L));
    KtvSessionApplicationService withResource = new KtvSessionApplicationService(sessionMapper, orderMapper,
        orderItemMapper, pricingPlanProvider, auditClient, orderAmounts, resourceClient);

    KtvSessionPo opened = withResource.open(session.getId(), 0, null);

    String snapshot = opened.getBillingRuleSnapshotJson();
    assertTrue(snapshot.contains("\"roomTypeCode\":\"SMALL\""), snapshot);
    assertTrue(snapshot.contains("\"roomUnitPrice\":3000"), snapshot);
    assertTrue(snapshot.contains("\"roomTypePriceApplied\":false"), snapshot);
  }

  private static ResourceStateClient.RoomSnapshot roomSnapshot(int capacity) {
    return new ResourceStateClient.RoomSnapshot("VIP 01", "V01", capacity, "VIP", "VIP 大包", 20000L, 6000L);
  }

  // —— 展示 + 结台统一口径：包厢费基数 = 房型单价 + 服务单价（已含 1 名标准服务人员）——

  /**
   * 新口径结台：房型 ¥30/小时（每块 1500）+ 服务 ¥50/小时（每块 2500）= 每块 4000；
   * 31 分钟 = 2 块 = 8000，明细名标注「含 1 名服务人员」，快照固化含 1 名免费服务人员。
   */
  @Test
  void close_chargesCombinedBaseWhenPlanIncludesServer() {
    KtvSessionPo session = session("OPEN");
    session.setBillingStartAt(LocalDateTime.now().minusMinutes(31));
    session.setBillingRuleSnapshotJson(combinedPlan().toSnapshotJson());
    OrderPo order = order("SERVING");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order);
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    service.close(session.getId());

    ArgumentCaptor<OrderItemPo> captor = ArgumentCaptor.forClass(OrderItemPo.class);
    verify(orderItemMapper).insert(captor.capture());
    OrderItemPo item = captor.getValue();
    assertEquals("包厢费（含 1 名服务人员）", item.getNameSnapshot());
    assertEquals(0, new BigDecimal("8000").compareTo(item.getTotalAmount()));
    assertEquals(0, new BigDecimal("4000").compareTo(item.getUnitPrice()));
    assertEquals(0, new BigDecimal("2").compareTo(item.getQuantity()));
    assertTrue(item.getPriceSnapshotJson().contains("\"roomFeeIncludesServer\":true"), item.getPriceSnapshotJson());
    assertTrue(item.getPriceSnapshotJson().contains("\"includedServerCount\":1"), item.getPriceSnapshotJson());
    assertTrue(item.getPriceSnapshotJson().contains("\"combinedUnitPrice\":8000"), item.getPriceSnapshotJson());
    assertTrue(item.getPriceSnapshotJson().contains("\"serverUnitPrice\":5000"), item.getPriceSnapshotJson());
  }

  /** 存量会话不追溯：历史快照没有 combinedUnitPrice/roomFeeIncludesServer 时仍只按房型价计。 */
  @Test
  void close_doesNotRetroChargeLegacySnapshotWithoutCombinedUnitPrice() {
    KtvSessionPo session = session("OPEN");
    session.setBillingStartAt(LocalDateTime.now().minusMinutes(31));
    session.setBillingRuleSnapshotJson("{\"billingUnit\":\"HOUR\",\"roomUnitPrice\":3000,"
        + "\"roomPricePerIncrement\":1500,\"defaultSessionMinutes\":120,\"freeWaitMinutes\":0,"
        + "\"overtimeRate\":1.5,\"incrementMinutes\":30,\"roundingDirection\":\"CONSUMER_FAVOR\","
        + "\"serverPricePerInc\":2500}");
    OrderPo order = order("SERVING");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order);
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    service.close(session.getId());

    ArgumentCaptor<OrderItemPo> captor = ArgumentCaptor.forClass(OrderItemPo.class);
    verify(orderItemMapper).insert(captor.capture());
    OrderItemPo item = captor.getValue();
    assertEquals("包厢计时费", item.getNameSnapshot(), "旧快照保持旧明细名");
    assertEquals(0, new BigDecimal("3000").compareTo(item.getTotalAmount()), "31 分钟 → 2 块 × 1500（不含服务费）");
    assertTrue(item.getPriceSnapshotJson().contains("\"roomFeeIncludesServer\":false"), item.getPriceSnapshotJson());
    assertTrue(item.getPriceSnapshotJson().contains("\"includedServerCount\":0"), item.getPriceSnapshotJson());
    assertTrue(item.getPriceSnapshotJson().contains("\"billableRoomPricePerIncrement\":1500"),
        item.getPriceSnapshotJson());
  }

  /** 新口径下「预估 == 结台」这条既有约束继续成立（两边同一基数）。 */
  @Test
  void liveEstimateMatchesCombinedCloseAmount() {
    KtvSessionPo session = session("OPEN");
    session.setBillingStartAt(LocalDateTime.now().minusMinutes(40).minusSeconds(5));
    session.setBillingRuleSnapshotJson(combinedPlan().toSnapshotJson());
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order("SERVING"));
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    service.fillLiveEstimate(session, 100L);
    long estimated = session.getEstimatedRoomFee();
    // 40 分钟 → 2 块 × 4000 = 8000
    assertEquals(8000L, estimated);

    service.close(session.getId());
    ArgumentCaptor<OrderItemPo> captor = ArgumentCaptor.forClass(OrderItemPo.class);
    verify(orderItemMapper).insert(captor.capture());
    assertEquals(estimated, captor.getValue().getTotalAmount().longValueExact(),
        "实时预估金额必须等于结台 ROOM_FEE 明细金额（同一基数）");
  }

  /** 快照还原口径：带 combinedUnitPrice 的新快照走新基数，历史快照走旧基数。 */
  @Test
  void planFromSnapshotHonoursBillingBaseMarker() {
    KtvPricingPlan fresh = KtvSessionApplicationService.planFromSnapshot(combinedPlan().toSnapshotJson());
    assertTrue(fresh.roomFeeIncludesServer());
    assertEquals(4000L, fresh.billableRoomPricePerIncrement());

    KtvPricingPlan legacy = KtvSessionApplicationService.planFromSnapshot(
        "{\"billingUnit\":\"HOUR\",\"roomUnitPrice\":3000,\"incrementMinutes\":30,"
            + "\"roundingDirection\":\"CONSUMER_FAVOR\",\"serverPricePerInc\":2500}");
    assertFalse(legacy.roomFeeIncludesServer(), "历史快照不得被追溯成新口径");
    assertEquals(1500L, legacy.billableRoomPricePerIncrement());
  }

  /** 新口径方案：房型 ¥30/小时（每块 1500）+ 服务 ¥50/小时（每块 2500），包厢费已含 1 名服务人员。 */
  private static KtvPricingPlan combinedPlan() {
    return new KtvPricingPlan(KtvBillingUnit.HOUR, 3000L, 120, 0, new BigDecimal("1.5"), 30,
        KtvRoundingDirection.CONSUMER_FAVOR, 2500L).withRoomFeeIncludesServer(true);
  }

  /**
   * 开台即计费：房费（含 1 名服务人员）明细在**开台**时就写入并重算订单金额，
   * 不再「开台 0、结台才出现」——门店开台后看到的应收就是房费。
   */
  @Test
  void openWritesRoomFeeItemAndRecalculatesOrder() {
    KtvSessionPo session = session("RESERVED");
    OrderPo order = order("DRAFT");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order);
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    service.open(session.getId(), 0, 2);

    ArgumentCaptor<OrderItemPo> captor = ArgumentCaptor.forClass(OrderItemPo.class);
    verify(orderItemMapper).insert(captor.capture());
    OrderItemPo item = captor.getValue();
    assertEquals("ROOM_FEE", item.getItemType());
    assertEquals("ACTIVE", item.getStatus());
    assertEquals(session.getOrderId(), item.getOrderId());
    assertEquals(1L, item.getTenantId());
    verify(orderMapper).updateById(order);
    assertEquals("SERVING", order.getStatus());
  }

  /**
   * 刷新任务：把「开台中」会话的房费明细补/刷新到当前时刻——既修存量（本轮之前开台、没有房费明细的订单），
   * 也让长开台订单的库里合计跟上时间。
   */
  @Test
  void refreshOpenRoomFeesRepairsMissingRoomFeeItem() {
    KtvSessionPo session = session("OPEN");
    session.setBillingStartAt(LocalDateTime.now().minusMinutes(60));
    OrderPo order = order("SERVING");
    when(sessionMapper.selectOpenSessions()).thenReturn(List.of(session));
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order);
    // 存量订单：库里还没有房费明细（mock 的 selectList 缺省空列表）
    when(orderItemMapper.selectList(any())).thenReturn(List.of());
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());

    int refreshed = service.refreshOpenRoomFees();

    assertEquals(1, refreshed);
    ArgumentCaptor<OrderItemPo> captor = ArgumentCaptor.forClass(OrderItemPo.class);
    verify(orderItemMapper).insert(captor.capture());
    assertEquals("ROOM_FEE", captor.getValue().getItemType());
    assertTrue(captor.getValue().getTotalAmount().signum() > 0, "开台 60 分钟至少一个计费块");
    verify(orderMapper).updateById(order);
  }

  /**
   * 定时任务线程没有租户上下文，而订单/明细 mapper 走租户行拦截器（缺上下文直接抛
   * 「租户上下文缺失」——线上就是这样整批刷新失败的）。刷新必须按会话租户逐个设置上下文，并在结束后清理。
   */
  @Test
  void refreshOpenRoomFeesSetsTenantContextPerSession() {
    KtvSessionPo session = session("OPEN");
    session.setBillingStartAt(LocalDateTime.now().minusMinutes(60));
    OrderPo order = order("SERVING");
    when(sessionMapper.selectOpenSessions()).thenReturn(List.of(session));
    when(orderItemMapper.selectList(any())).thenReturn(List.of());
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());
    java.util.List<Long> tenantSeenByMapper = new java.util.ArrayList<>();
    when(orderMapper.selectById(session.getOrderId())).thenAnswer(invocation -> {
      tenantSeenByMapper.add(TenantContextHolder.tenantIdOrNull());
      return order;
    });
    TenantContextHolder.clear();

    service.refreshOpenRoomFees();

    assertEquals(List.of(1L), tenantSeenByMapper, "刷新期间必须带上该会话的租户上下文");
    assertNull(TenantContextHolder.tenantIdOrNull(), "刷新结束必须清理上下文，不能污染线程");
  }

  /**
   * 定时刷新也必须与看板/账单/结台同一「停表」口径：PAUSED 会话的房费停在 pause_started_at，
   * 而不是按 now 一直长钱（否则库内快照与看板/账单三个数会互相打架）。
   *
   * <p>开台 T-150min、暂停于 T-120min：计费 30 分钟 = 1 块 = 1500 分；按 now 会算成 5 块 = 7500 分。
   */
  @Test
  void refreshOpenRoomFeesStopsAtPauseStartedAtForPausedSession() {
    KtvSessionPo session = session("PAUSED");
    session.setBillingStartAt(LocalDateTime.now().minusMinutes(150));
    session.setPauseStartedAt(LocalDateTime.now().minusMinutes(120));
    OrderPo order = order("SERVING");
    when(sessionMapper.selectOpenSessions()).thenReturn(List.of(session));
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order);
    when(orderItemMapper.selectList(any())).thenReturn(List.of());
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());

    assertEquals(1, service.refreshOpenRoomFees());

    ArgumentCaptor<OrderItemPo> captor = ArgumentCaptor.forClass(OrderItemPo.class);
    verify(orderItemMapper).insert(captor.capture());
    assertEquals(0, new BigDecimal("1500").compareTo(captor.getValue().getTotalAmount()),
        "挂单期间库内房费也必须停表（30 分钟 = 1 块）");
    assertEquals(0, new BigDecimal("1").compareTo(captor.getValue().getQuantity()));
  }

  /**
   * 同一订单存在多个会话（多次开台）时，订单投影必须取「**当前这一次**」：
   * 进行中的 OPEN/PAUSED 优先于任何已结束会话，同级再取更晚创建（id 更大）的那条。
   *
   * <p>回归「多次开台被累计」：取第一条会把上一次开台的状态/计时/估算金额显示到收银台与订单管理上。
   */
  @Test
  void listByOrderIdsPrefersCurrentSessionWhenOrderWasOpenedMoreThanOnce() {
    KtvSessionPo olderClosed = session("CLOSED");
    olderClosed.setId(11L);
    olderClosed.setOrderId(88L);
    KtvSessionPo currentOpen = session("OPEN");
    currentOpen.setId(12L);
    currentOpen.setOrderId(88L);
    when(sessionMapper.selectList(any())).thenReturn(List.of(olderClosed, currentOpen));

    var byOrder = service.listByOrderIds(List.of(88L));

    assertEquals(12L, byOrder.get(88L).getId(), "有进行中的会话时必须取进行中的那一次");
  }

  /** 都是已结束会话时取更晚创建（id 更大）的那条，而不是列表里的第一条。 */
  @Test
  void listByOrderIdsPrefersLatestAmongFinishedSessions() {
    KtvSessionPo first = session("CLOSED");
    first.setId(21L);
    first.setOrderId(99L);
    KtvSessionPo second = session("CLOSED");
    second.setId(22L);
    second.setOrderId(99L);
    when(sessionMapper.selectList(any())).thenReturn(List.of(first, second));

    assertEquals(22L, service.listByOrderIds(List.of(99L)).get(99L).getId());
  }

  /** 待开台（RESERVED）比已结束会话更「当前」：预约刚开出来的新一次消费不能被旧的已结台会话盖掉。 */
  @Test
  void listByOrderIdsPrefersReservedOverFinishedSession() {
    KtvSessionPo closed = session("CLOSED");
    closed.setId(31L);
    closed.setOrderId(77L);
    KtvSessionPo reserved = session("RESERVED");
    reserved.setId(30L);
    reserved.setOrderId(77L);
    when(sessionMapper.selectList(any())).thenReturn(List.of(closed, reserved));

    assertEquals(30L, service.listByOrderIds(List.of(77L)).get(77L).getId());
  }

  private static KtvSessionPo session(String status) {
    KtvSessionPo po = new KtvSessionPo();
    po.setId(1L);
    po.setTenantId(1L);
    po.setOrderId(10L);
    po.setStatus(status);
    po.setPausedSeconds(0);
    return po;
  }
  private static OrderPo order(String status) {
    OrderPo po = new OrderPo();
    po.setId(10L);
    po.setTenantId(1L);
    po.setStoreId(100L);
    po.setStatus(status);
    // 结算按 expectedVersion 做乐观锁：真实数据 version 列缺省 0，这里补上避免拆箱 NPE。
    po.setVersion(0);
    return po;
  }

  /**
   * 结台即结算：结台已经把包厢计时费写入明细并重算金额，默认直接把订单推进到「待收款」，
   * 门店不用再多点一次「结算」（2026-09-19 优化）。
   */
  @Test
  void closeAutoSettlesOrderWhenNoPendingCustomerItems() {
    KtvSessionPo session = session("OPEN");
    session.setBillingStartAt(LocalDateTime.now().minusMinutes(30));
    OrderPo order = order("SERVING");
    SettlementApplicationService settlement = mock(SettlementApplicationService.class);
    KtvSessionApplicationService serviceWithSettle = new KtvSessionApplicationService(sessionMapper, orderMapper,
        orderItemMapper, pricingPlanProvider, auditClient, orderAmounts, null, settlement);
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order);
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    serviceWithSettle.close(session.getId());

    verify(settlement).settle(order.getId(), 0);
  }

  /**
   * 有客户自助加项待确认时**不**自动结算：它们不计入应付（与账单同一口径），
   * 自动结算会让顾客点的东西从账单里消失（漏收）——此时保持待结算，门店先确认/拒绝再结算。
   */
  @Test
  void closeKeepsWaitingSettlementWhenCustomerItemsPendingApproval() {
    KtvSessionPo session = session("OPEN");
    session.setBillingStartAt(LocalDateTime.now().minusMinutes(30));
    OrderPo order = order("SERVING");
    OrderAmountApplicationService amounts = mock(OrderAmountApplicationService.class);
    when(amounts.hasPendingApprovalItems(order.getId())).thenReturn(true);
    SettlementApplicationService settlement = mock(SettlementApplicationService.class);
    KtvSessionApplicationService serviceWithSettle = new KtvSessionApplicationService(sessionMapper, orderMapper,
        orderItemMapper, pricingPlanProvider, auditClient, amounts, null, settlement);
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order);
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    serviceWithSettle.close(session.getId());

    verifyNoInteractions(settlement);
    assertEquals("WAITING_SETTLEMENT", order.getStatus());
  }

  private static KtvPricingPlan plan() {
    return new KtvPricingPlan(KtvBillingUnit.HOUR, 3000L, 120, 0, new BigDecimal("1.5"), 30,
        KtvRoundingDirection.CONSUMER_FAVOR, 5000L);
  }

  // ---------------------------------------------------------------------------------------------
  // 领域失败/成功留痕：/business/** 无 BFF 兜底，开台/结台/暂停/恢复/取消/转台/暂停修正都必须两种结果都留痕。
  // ---------------------------------------------------------------------------------------------

  @Test
  void open_writesSucceededAudit() {
    KtvSessionPo session = session("RESERVED");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(orderMapper.selectById(session.getOrderId())).thenReturn(order("DRAFT"));
    when(pricingPlanProvider.resolve(1L, 100L)).thenReturn(plan());
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    service.open(session.getId(), 0);

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_session.open", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    assertNull(record.errorCode());
  }

  @Test
  void open_writesFailureAuditWithStableErrorCode() {
    when(sessionMapper.selectById(1L)).thenReturn(session("OPEN"));

    BusinessException ex = assertThrows(BusinessException.class, () -> service.open(1L, 0));

    assertEquals("ORDER_STATUS_INVALID", ex.getCode());
    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_session.open", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("ORDER_STATUS_INVALID", record.errorCode());
    assertNull(record.idempotencyKey());
    assertEquals("ktv_session", record.resourceType());
    assertEquals("1", record.resourceId());
  }

  @Test
  void close_writesFailureAuditWithStableErrorCode() {
    when(sessionMapper.selectById(1L)).thenReturn(session("RESERVED"));

    BusinessException ex = assertThrows(BusinessException.class, () -> service.close(1L));

    assertEquals("ORDER_STATUS_INVALID", ex.getCode());
    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_session.close", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("ORDER_STATUS_INVALID", record.errorCode());
  }

  @Test
  void pause_writesSucceededAudit() {
    KtvSessionPo session = session("OPEN");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    service.pause(session.getId());

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_session.pause", record.action());
    assertEquals("会话暂停", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
  }

  @Test
  void pause_writesFailureAudit() {
    when(sessionMapper.selectById(1L)).thenReturn(session("RESERVED"));

    assertThrows(BusinessException.class, () -> service.pause(1L));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_session.pause", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("ORDER_STATUS_INVALID", record.errorCode());
  }

  @Test
  void resume_writesSucceededAudit() {
    KtvSessionPo session = session("PAUSED");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    service.resume(session.getId());

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_session.resume", record.action());
    assertEquals("会话恢复", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
  }

  @Test
  void resume_writesFailureAudit() {
    when(sessionMapper.selectById(1L)).thenReturn(session("OPEN"));

    assertThrows(BusinessException.class, () -> service.resume(1L));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_session.resume", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("ORDER_STATUS_INVALID", record.errorCode());
  }

  @Test
  void cancel_writesSucceededAudit() {
    KtvSessionPo session = session("RESERVED");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    service.cancel(session.getId());

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_session.cancel", record.action());
    assertEquals("会话取消", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
  }

  @Test
  void cancel_writesFailureAudit() {
    when(sessionMapper.selectById(1L)).thenReturn(session("OPEN"));

    assertThrows(BusinessException.class, () -> service.cancel(1L));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_session.cancel", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("ORDER_STATUS_INVALID", record.errorCode());
  }

  @Test
  void transfer_writesSucceededAudit() {
    KtvSessionPo session = session("OPEN");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(sessionMapper.transferRoom(session)).thenReturn(1);

    service.transfer(session.getId(), 3002L);

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.transfer", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
  }

  @Test
  void transfer_writesFailureAudit() {
    KtvSessionPo session = session("OPEN");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(sessionMapper.transferRoom(session)).thenReturn(0);

    BusinessException ex = assertThrows(BusinessException.class, () -> service.transfer(session.getId(), 3002L));

    assertEquals("SESSION_VERSION_CONFLICT", ex.getCode());
    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.transfer", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("SESSION_VERSION_CONFLICT", record.errorCode());
  }

  @Test
  void correctPause_writesSucceededAudit() {
    KtvSessionPo session = session("OPEN");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);
    when(sessionMapper.updateWithVersion(any())).thenReturn(1);

    service.correctPause(session.getId(), 60);

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_session.correct_pause", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
  }

  @Test
  void correctPause_writesFailureAudit() {
    KtvSessionPo session = session("OPEN");
    when(sessionMapper.selectById(session.getId())).thenReturn(session);

    ApiException ex = assertThrows(ApiException.class, () -> service.correctPause(session.getId(), -1));

    assertEquals("AMOUNT_INVALID", ex.getCode());
    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("order.ktv_session.correct_pause", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("AMOUNT_INVALID", record.errorCode());
  }

  private AuditClient.AuditRecord capturedAudit() {
    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    return captor.getValue();
  }
}

package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.dto.PendingApprovalView;
import io.openware.platform.order.infra.cache.PendingApprovalCache;
import io.openware.platform.order.infra.persistence.mapper.OrderItemMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.po.KtvSessionPo;
import io.openware.platform.order.infra.persistence.po.OrderItemPo;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 「客户待确认加项」聚合口径（后台角标 / 收银台卡片 / 订单管理列表 / App 横幅共用的数据源）：
 * 门店隔离、计数与金额按全量、混币种不求和、revision 变化、缓存命中与写后失效。
 */
class PendingApprovalApplicationServiceTest {

  private static final long TENANT_ID = 100L;
  private static final long STORE_ID = 100L;
  private static final long OTHER_STORE_ID = 999L;

  private OrderItemMapper orderItemMapper;
  private OrderMapper orderMapper;
  private KtvSessionApplicationService ktvSessionService;
  private PendingApprovalCache cache;
  private PendingApprovalApplicationService service;

  @BeforeEach
  void setUp() {
    orderItemMapper = mock(OrderItemMapper.class);
    orderMapper = mock(OrderMapper.class);
    ktvSessionService = mock(KtvSessionApplicationService.class);
    // 缓存 TTL 用 3s（与生产默认一致），便于「命中缓存」与「失效后回源」两个分支都真实执行。
    cache = new PendingApprovalCache(3000);
    service = new PendingApprovalApplicationService(orderItemMapper, orderMapper, ktvSessionService, cache);
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 42L, 1));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  @Test
  void groupsByOrderAndSkipsOtherStores() {
    when(orderItemMapper.selectList(any())).thenReturn(List.of(
        item(11L, 1L, "精酿啤酒", "6", "10800", "CNY", now(1)),
        item(12L, 1L, "果盘", "1", "8800", "CNY", now(2)),
        item(13L, 2L, "陪唱服务", "1", "30000", "CNY", now(3))));
    when(orderMapper.selectBatchIds(any())).thenReturn(List.of(
        order(1L, STORE_ID, "O202609190001"),
        order(2L, OTHER_STORE_ID, "O202609190002")));
    KtvSessionPo session = new KtvSessionPo();
    session.setId(9L);
    session.setRoomNameSnapshot("K01");
    session.setRoomCodeSnapshot("K01");
    session.setStatus("OPEN");
    when(ktvSessionService.listByOrderIds(any())).thenReturn(Map.of(1L, session));

    PendingApprovalView view = service.pendingForCurrentStore();

    // 别的门店的加项不计入本门店提醒
    assertEquals(2L, view.pendingCount());
    assertEquals(19600L, view.pendingAmount());
    assertEquals("CNY", view.currencyCode());
    assertFalse(view.mixedCurrency());
    assertEquals(1, view.orders().size());
    PendingApprovalView.PendingOrder group = view.orders().get(0);
    assertEquals(1L, group.orderId());
    assertEquals("O202609190001", group.orderNo());
    assertEquals("K01", group.roomName());
    assertEquals(2L, group.pendingCount());
    assertEquals(19600L, group.pendingAmount());
    assertEquals(2, group.items().size());
    // 明细按提交时间正序（先到先处理）
    assertEquals(11L, group.items().get(0).id());
    assertEquals(12L, group.items().get(1).id());
    // revision = **本门店命中行**的最大 id（别的门店的加项不参与本门店提醒），供客户端「未变则跳过重渲染」
    assertEquals(12L, view.revision());
  }

  @Test
  void mixedCurrencyIsFlaggedAndNotSummedAsOneCurrency() {
    when(orderItemMapper.selectList(any())).thenReturn(List.of(
        item(21L, 1L, "啤酒", "1", "100", "CNY", now(1)),
        item(22L, 1L, "啤酒", "1", "200", "USD", now(2))));
    when(orderMapper.selectBatchIds(any())).thenReturn(List.of(order(1L, STORE_ID, "O1")));
    when(ktvSessionService.listByOrderIds(any())).thenReturn(Map.of());

    PendingApprovalView view = service.pendingForCurrentStore();

    assertTrue(view.mixedCurrency());
    assertNull(view.currencyCode(), "混币种不得给出单一币种，调用方不得相加");
    assertEquals(300L, view.pendingAmount());
  }

  @Test
  void emptyWhenNoPendingItems() {
    when(orderItemMapper.selectList(any())).thenReturn(List.of());

    PendingApprovalView view = service.pendingForCurrentStore();

    assertEquals(0L, view.pendingCount());
    assertEquals(0L, view.pendingAmount());
    assertTrue(view.orders().isEmpty());
    verify(orderMapper, never()).selectBatchIds(any());
  }

  @Test
  void secondReadHitsCacheUntilInvalidated() {
    when(orderItemMapper.selectList(any())).thenReturn(List.of(item(31L, 1L, "啤酒", "1", "100", "CNY", now(1))));
    when(orderMapper.selectBatchIds(any())).thenReturn(List.of(order(1L, STORE_ID, "O1")));
    when(ktvSessionService.listByOrderIds(any())).thenReturn(Map.of());

    service.pendingForCurrentStore();
    service.pendingForCurrentStore();
    // 命中缓存：只回源一次
    verify(orderItemMapper, times(1)).selectList(any());

    // 写路径失效（客户提交加项 / 确认 / 拒绝）后必须回源，角标立即更新
    service.invalidateCurrentStore();
    service.pendingForCurrentStore();
    verify(orderItemMapper, times(2)).selectList(any());
  }

  /**
   * 包厢展示名：门店必须一眼看出「是哪间包厢的需求」，因此会话名称快照为空时要逐级兜底
   * （编码快照 → 按 roomResourceId 回源资源服务），不能只给一个空串。
   */
  @Test
  void roomNameFallsBackToCodeThenToResourceService() {
    when(orderItemMapper.selectList(any())).thenReturn(List.of(item(41L, 1L, "精酿啤酒", "1", "100", "CNY", now(1))));
    when(orderMapper.selectBatchIds(any())).thenReturn(List.of(order(1L, STORE_ID, "O1")));
    // 会话只剩编码快照（开台时资源服务给不出名称）
    KtvSessionPo codeOnly = session(1L, 9L, "", "K01");
    when(ktvSessionService.listByOrderIds(any())).thenReturn(Map.of(1L, codeOnly));

    assertEquals("K01", service.pendingForCurrentStore().orders().get(0).roomName());

    // 名称/编码快照都为空（开台时资源服务不可达）：按 roomResourceId 回源一次
    service.invalidateCurrentStore();
    KtvSessionPo blank = session(1L, 9L, null, "   ");
    when(ktvSessionService.listByOrderIds(any())).thenReturn(Map.of(1L, blank));
    when(ktvSessionService.roomNameFromResource(9L)).thenReturn("小包 K01");

    assertEquals("小包 K01", service.pendingForCurrentStore().orders().get(0).roomName());

    // 回源也拿不到（资源服务仍不可达）：返回 null，由前端统一显示「未关联包厢」
    service.invalidateCurrentStore();
    when(ktvSessionService.roomNameFromResource(9L)).thenReturn(null);

    assertNull(service.pendingForCurrentStore().orders().get(0).roomName());
  }

  /** 订单没有 KTV 会话（非包厢单）：包厢字段为 null，但提醒本身必须照常给出。 */
  @Test
  void roomNameIsNullWhenOrderHasNoSession() {
    when(orderItemMapper.selectList(any())).thenReturn(List.of(item(51L, 1L, "精酿啤酒", "1", "100", "CNY", now(1))));
    when(orderMapper.selectBatchIds(any())).thenReturn(List.of(order(1L, STORE_ID, "O1")));
    when(ktvSessionService.listByOrderIds(any())).thenReturn(Map.of());

    PendingApprovalView view = service.pendingForCurrentStore();

    assertNull(view.orders().get(0).roomName());
    assertNull(view.orders().get(0).roomCode());
    assertEquals(1L, view.pendingCount());
  }

  private static OrderItemPo item(Long id, Long orderId, String name, String quantity, String amount,
                                  String currency, LocalDateTime createdAt) {
    OrderItemPo po = new OrderItemPo();
    po.setId(id);
    po.setTenantId(TENANT_ID);
    po.setOrderId(orderId);
    po.setNameSnapshot(name);
    po.setQuantity(new BigDecimal(quantity));
    po.setUnitPrice(new BigDecimal(amount));
    po.setTotalAmount(new BigDecimal(amount));
    po.setCurrencyCode(currency);
    po.setStatus("PENDING_APPROVAL");
    po.setCreatedAt(createdAt);
    return po;
  }

  private static OrderPo order(Long id, Long storeId, String orderNo) {
    OrderPo po = new OrderPo();
    po.setId(id);
    po.setTenantId(TENANT_ID);
    po.setStoreId(storeId);
    po.setOrderNo(orderNo);
    return po;
  }

  /** KTV 会话：包厢快照可能为空（开台时资源服务不可达），只留 roomResourceId。 */
  private static KtvSessionPo session(Long orderId, Long roomResourceId, String roomName, String roomCode) {
    KtvSessionPo po = new KtvSessionPo();
    po.setId(9L);
    po.setOrderId(orderId);
    po.setRoomResourceId(roomResourceId);
    po.setRoomNameSnapshot(roomName);
    po.setRoomCodeSnapshot(roomCode);
    po.setStatus("OPEN");
    return po;
  }

  private static LocalDateTime now(int plusSeconds) {
    return LocalDateTime.of(2026, 9, 19, 20, 0, 0).plusSeconds(plusSeconds);
  }
}

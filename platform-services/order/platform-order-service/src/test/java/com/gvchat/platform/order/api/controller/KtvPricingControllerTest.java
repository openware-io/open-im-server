package com.gvchat.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.currency.Currency;
import com.gvchat.infrastructure.currency.CurrencyContextHolder;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.domain.ktv.model.KtvBillingUnit;
import com.gvchat.platform.order.domain.ktv.model.KtvPricingPlan;
import com.gvchat.platform.order.domain.ktv.model.KtvRoundingDirection;
import com.gvchat.platform.order.infra.client.ResourceStateClient;
import com.gvchat.platform.order.infra.config.DefaultKtvPricingPlanProvider;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * /business/ktv/pricing 响应契约：返回**实际生效**的单价、分项与合计与 displayText，
 * 带 resourceId 时按该包厢房型取价（房型价命中 / 未定价回退门店价），不带时返回门店级方案。
 * C 端「包厢价格 = 房型单价 + 服务单价」，所以 combinedUnitPrice 必须是两者之和。
 */
class KtvPricingControllerTest {

  private DefaultKtvPricingPlanProvider pricingPlanProvider;
  private ResourceStateClient resourceStateClient;
  private KtvPricingController controller;

  @BeforeEach
  void setUp() {
    pricingPlanProvider = mock(DefaultKtvPricingPlanProvider.class);
    resourceStateClient = mock(ResourceStateClient.class);
    controller = new KtvPricingController(pricingPlanProvider, resourceStateClient);
    TenantContextHolder.set(new TenantContext(1L, 1L, 100L, 1L, 1, List.of("ktv.session.open")));
    // 币种由签名上下文提供（TenantContextFilter 从 claim currency 写入旁路 holder）：
    // 本类既有断言是 CNY 租户口径，因此显式写入 CNY；缺省 USD 由独立用例覆盖。
    CurrencyContextHolder.set(Currency.CNY);
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
    CurrencyContextHolder.clear();
  }

  /** 命中房型价：单价取房型价，服务单价取房型字典的 server_unit_price，合计 = 两者之和。 */
  @Test
  void current_appliesRoomTypePriceWhenRoomTypeIsPriced() {
    when(resourceStateClient.room(3001L)).thenReturn(Optional.of(
        new ResourceStateClient.RoomSnapshot("VIP 01", "V01", 12, "VIP", "VIP 大包", 20000L, 6000L)));
    when(pricingPlanProvider.effectivePlanForRoomType(eq(1L), eq(100L), eq("VIP"), eq("VIP 大包"),
        eq(20000L), eq(6000L))).thenReturn(storeLevelPlan().forRoomType("VIP", "VIP 大包", 20000L, 6000L));
    when(pricingPlanProvider.usesTenantPlan(any(), any())).thenReturn(true);

    KtvPricingController.PricingView view = controller.current(null, 3001L, null);

    assertEquals(20000L, view.roomUnitPrice(), "生效单价必须是房型单价");
    assertEquals(3000L, view.serverPricePerInc(), "服务费也按房型单价换算");
    assertEquals(6000L, view.serverUnitPrice(), "服务单价取房型字典 server_unit_price（每计费单位）");
    assertEquals(26000L, view.combinedUnitPrice(), "合计 = 房型单价 + 服务单价");
    assertEquals("VIP", view.appliedRoomTypeCode());
    assertTrue(view.roomTypePriceApplied());
    assertTrue(view.fromTenantPlan());
    assertTrue(view.displayText().contains("¥260.00/小时（房型 ¥200.00 + 服务 ¥60.00）"), view.displayText());
    assertTrue(view.displayText().contains("房型「VIP 大包」生效单价 ¥200.00/小时"), view.displayText());
    assertTrue(view.displayText().contains("每 30 分钟 ¥130.00"), view.displayText());
    assertTrue(view.displayText().contains("标准 2.0 小时"), view.displayText());
  }

  /** 该房型未定价：房费与服务单价一起回退门店级，displayText 明确说明回退（页面价格与账单一致且可解释）。 */
  @Test
  void current_fallsBackToStorePriceWhenRoomTypeIsNotPriced() {
    when(resourceStateClient.room(3001L)).thenReturn(Optional.of(
        new ResourceStateClient.RoomSnapshot("小包 01", "S01", 6, "SMALL", "小包", null, null)));
    when(pricingPlanProvider.effectivePlanForRoomType(eq(1L), eq(100L), eq("SMALL"), eq("小包"),
        eq(null), eq(null))).thenReturn(storeLevelPlan().forRoomType("SMALL", "小包", null, null));
    when(pricingPlanProvider.usesTenantPlan(any(), any())).thenReturn(false);

    KtvPricingController.PricingView view = controller.current(null, 3001L, null);

    assertEquals(3000L, view.roomUnitPrice());
    assertEquals(2500L, view.serverPricePerInc());
    assertEquals(5000L, view.serverUnitPrice(), "回退门店级：2500 分/30 分钟 → 5000 分/小时");
    assertEquals(8000L, view.combinedUnitPrice());
    assertEquals("SMALL", view.appliedRoomTypeCode());
    assertFalse(view.roomTypePriceApplied());
    assertFalse(view.fromTenantPlan());
    assertTrue(view.displayText().contains("¥80.00/小时（房型 ¥30.00 + 服务 ¥50.00）"), view.displayText());
    assertTrue(view.displayText().contains("房型「小包」未定价，回退门店单价"), view.displayText());
  }

  /** 不带 resourceId/roomTypeCode：返回门店级方案，房型字段为空，行为与改造前一致。 */
  @Test
  void current_withoutRoomTypeReturnsStoreLevelPlan() {
    when(pricingPlanProvider.effectivePlanForRoomType(eq(1L), eq(100L), eq(null), eq(null), eq(null), eq(null)))
        .thenReturn(storeLevelPlan());

    KtvPricingController.PricingView view = controller.current(null, null, null);

    assertEquals(3000L, view.roomUnitPrice());
    assertEquals(2500L, view.serverPricePerInc());
    assertEquals(5000L, view.serverUnitPrice());
    assertEquals(8000L, view.combinedUnitPrice());
    assertTrue(view.appliedRoomTypeCode() == null);
    assertTrue(view.unitPriceByRoomType().isEmpty());
    assertTrue(view.displayText().startsWith("¥80.00/小时（房型 ¥30.00 + 服务 ¥50.00） · 30 分钟递增"), view.displayText());
    assertFalse(view.displayText().contains("房型「"), view.displayText());
  }

  /** 服务单价为 0：不出现「+ ¥0.00」，首段退回房型单价（C 端同一约定）。 */
  @Test
  void current_omitsServerPartWhenServerPriceIsZero() {
    KtvPricingPlan noServer = new KtvPricingPlan(KtvBillingUnit.HOUR, 3000L, 120, 0, new BigDecimal("1.5"), 30,
        KtvRoundingDirection.CONSUMER_FAVOR, 0L);
    when(pricingPlanProvider.effectivePlanForRoomType(eq(1L), eq(100L), eq(null), eq(null), eq(null), eq(null)))
        .thenReturn(noServer);

    KtvPricingController.PricingView view = controller.current(null, null, null);

    assertEquals(0L, view.serverUnitPrice());
    assertEquals(3000L, view.combinedUnitPrice(), "服务为 0 时合计就是房型单价");
    assertEquals("¥30.00/小时 · 30 分钟递增 · 每 30 分钟 ¥15.00 · 标准 2.0 小时", view.displayText());
    assertFalse(view.displayText().contains("+"), view.displayText());
  }

  /** 资源服务不可达（拿不到快照）时降级为门店级方案，不阻断预约页展示。 */
  @Test
  void current_degradesToStorePlanWhenResourceServiceUnavailable() {
    when(resourceStateClient.room(3001L)).thenReturn(Optional.empty());
    when(pricingPlanProvider.effectivePlanForRoomType(eq(1L), eq(100L), eq(null), eq(null), eq(null), eq(null)))
        .thenReturn(storeLevelPlan());

    KtvPricingController.PricingView view = controller.current(null, 3001L, null);

    assertEquals(3000L, view.roomUnitPrice());
    assertTrue(view.appliedRoomTypeCode() == null);
  }

  @Test
  void current_requiresTenantContext() {
    TenantContextHolder.clear();

    ApiException ex = assertThrows(ApiException.class, () -> controller.current(null, null, null));

    assertEquals(401, ex.getStatus());
    assertEquals("TENANT_CONTEXT_MISSING", ex.getCode());
  }

  private static KtvPricingPlan storeLevelPlan() {
    return new KtvPricingPlan(KtvBillingUnit.HOUR, 3000L, 120, 0, new BigDecimal("1.5"), 30,
        KtvRoundingDirection.CONSUMER_FAVOR, 2500L);
  }
}

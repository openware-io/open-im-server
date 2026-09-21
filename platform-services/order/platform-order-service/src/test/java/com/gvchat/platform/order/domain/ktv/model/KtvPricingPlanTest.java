package com.gvchat.platform.order.domain.ktv.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 按房型定价取值顺序：房型字典单价（资源侧） &gt; 计价方案按房型单价 &gt; 门店级单价。
 * 覆盖「房型价命中」与「缺该房型回退门店价」两条，并校验快照固化了实际使用的房型与单价。
 */
class KtvPricingPlanTest {

  /** 门店级方案：3000 分/小时，30 分钟递增，服务人员 5000 分/小时（每 30 分钟 2500）。 */
  private static KtvPricingPlan storeLevelPlan() {
    return new KtvPricingPlan(KtvBillingUnit.HOUR, 3000L, 120, 0, new BigDecimal("1.5"), 30,
        KtvRoundingDirection.CONSUMER_FAVOR, 2500L);
  }

  @Test
  void forRoomTypeAppliesRoomTypePricesWhenConfigured() {
    KtvPricingPlan plan = storeLevelPlan().forRoomType("VIP", "VIP 大包", 20000L, 6000L);

    assertEquals(20000L, plan.roomUnitPrice(), "命中房型价：房费取房型单价");
    assertTrue(plan.roomTypePriceApplied());
    assertEquals("VIP", plan.appliedRoomTypeCode());
    assertEquals("VIP 大包", plan.appliedRoomTypeName());
    // 房型服务人员单价 6000/小时 + 30 分钟递增 → 每递增粒度 3000 分
    assertEquals(3000L, plan.serverPricePerInc());
    // 每递增粒度房费 = 20000 × 30 / 60 = 10000
    assertEquals(10000L, plan.roomPricePerIncrement());
    // 超时费率、递增粒度、标准时长仍取门店级方案（房型只覆盖单价）
    assertEquals(30, plan.effectiveIncrementMinutes());
    assertEquals(120, plan.defaultSessionMinutes());
    assertEquals(0, new BigDecimal("1.5").compareTo(plan.effectiveOvertimeRate()));
  }

  @Test
  void forRoomTypeFallsBackToStoreLevelPriceWhenRoomTypeHasNoPrice() {
    KtvPricingPlan plan = storeLevelPlan().forRoomType("SMALL", "小包", null, null);

    assertEquals(3000L, plan.roomUnitPrice(), "该房型未定价：回退门店级单价");
    assertEquals(2500L, plan.serverPricePerInc());
    assertFalse(plan.roomTypePriceApplied());
    // 房型本身仍然被记录（快照要固化「实际使用的房型」，即使它没定价）
    assertEquals("SMALL", plan.appliedRoomTypeCode());
    assertEquals("小包", plan.appliedRoomTypeName());
  }

  @Test
  void forRoomTypeFallsBackWhenRoomTypePriceIsNotPositive() {
    KtvPricingPlan plan = storeLevelPlan().forRoomType("SMALL", "小包", 0L, -100L);

    assertEquals(3000L, plan.roomUnitPrice());
    assertEquals(2500L, plan.serverPricePerInc());
    assertFalse(plan.roomTypePriceApplied());
  }

  /** 资源未设置房型（roomTypeCode 为空）：整单走门店级单价，且不记录房型。 */
  @Test
  void forRoomTypeWithoutRoomTypeKeepsStoreLevelPlan() {
    KtvPricingPlan plan = storeLevelPlan().forRoomType(null, null, 20000L, 6000L);

    assertEquals(3000L, plan.roomUnitPrice());
    assertEquals(2500L, plan.serverPricePerInc());
    assertFalse(plan.roomTypePriceApplied());
    assertNull(plan.appliedRoomTypeCode());
  }

  /** 资源侧未定价但计价方案按房型下发了单价：命中方案按房型价（第二取值来源）。 */
  @Test
  void forRoomTypeFallsBackToPlanRoomTypePriceWhenResourceHasNoPrice() {
    KtvPricingPlan withPlanPrices = new KtvPricingPlan(KtvBillingUnit.HOUR, 3000L, 120, 0, new BigDecimal("1.5"), 30,
        KtvRoundingDirection.CONSUMER_FAVOR, 2500L, Map.of("VIP", 18000L), null, null, false);

    KtvPricingPlan plan = withPlanPrices.forRoomType("VIP", "VIP 大包", null, null);

    assertEquals(18000L, plan.roomUnitPrice());
    assertTrue(plan.roomTypePriceApplied());
  }

  /** 快照必须固化实际使用的房型与单价：命中房型价时 roomUnitPrice/roomTypeCode 都是房型的口径。 */
  @Test
  void snapshotCarriesAppliedRoomTypeAndEffectivePrices() {
    String snapshot = storeLevelPlan().forRoomType("VIP", "VIP 大包", 20000L, 6000L).toSnapshotJson();

    assertTrue(snapshot.contains("\"roomUnitPrice\":20000"), snapshot);
    assertTrue(snapshot.contains("\"roomPricePerIncrement\":10000"), snapshot);
    assertTrue(snapshot.contains("\"serverPricePerInc\":3000"), snapshot);
    assertTrue(snapshot.contains("\"roomTypeCode\":\"VIP\""), snapshot);
    assertTrue(snapshot.contains("\"roomTypeName\":\"VIP 大包\""), snapshot);
    assertTrue(snapshot.contains("\"roomTypePriceApplied\":true"), snapshot);
    assertTrue(snapshot.contains("\"incrementMinutes\":30"), snapshot);
    assertTrue(snapshot.contains("\"roundingDirection\":\"CONSUMER_FAVOR\""), snapshot);
  }

  /** C 端口径「包厢价格 = 房型单价 + 服务单价」：分项与合计都要能算出来（房型服务单价命中）。 */
  @Test
  void combinedUnitPriceSumsRoomTypeAndServerUnitPrice() {
    KtvPricingPlan plan = storeLevelPlan().forRoomType("VIP", "VIP 大包", 20000L, 6000L);

    assertEquals(6000L, plan.serverUnitPrice(), "服务单价取房型字典 server_unit_price（每计费单位）");
    assertEquals(26000L, plan.combinedUnitPrice(), "合计 = 房型单价 20000 + 服务单价 6000");
    assertEquals(13000L, plan.combinedPricePerIncrement(), "每递增粒度合计 = 10000 + 3000");
  }

  /** 房型未定价：房费与服务单价一起回退门店级，合计同样成立（不出现「只有房费没有服务费」的半截口径）。 */
  @Test
  void combinedUnitPriceFallsBackToStoreLevelServerPrice() {
    KtvPricingPlan plan = storeLevelPlan().forRoomType("SMALL", "小包", null, null);

    assertEquals(5000L, plan.serverUnitPrice(), "门店级 2500 分/30 分钟 → 5000 分/小时");
    assertEquals(8000L, plan.combinedUnitPrice());
  }

  /** 服务单价为 0（未配置服务人员价）：合计等于房型单价，展示端据此不拼「+ ¥0.00」。 */
  @Test
  void combinedUnitPriceEqualsRoomPriceWhenServerPriceIsZero() {
    KtvPricingPlan plan = new KtvPricingPlan(KtvBillingUnit.HOUR, 3000L, 120, 0, new BigDecimal("1.5"), 30,
        KtvRoundingDirection.CONSUMER_FAVOR, 0L);

    assertEquals(0L, plan.serverUnitPrice());
    assertEquals(3000L, plan.combinedUnitPrice());
  }

  /** 快照固化分项与合计：事后对账要能看出「当时 C 端展示的是哪两个数、合计多少」。 */
  @Test
  void snapshotFreezesItemizedAndCombinedUnitPrice() {
    String snapshot = storeLevelPlan().forRoomType("VIP", "VIP 大包", 20000L, 6000L).toSnapshotJson();

    assertTrue(snapshot.contains("\"serverUnitPrice\":6000"), snapshot);
    assertTrue(snapshot.contains("\"combinedUnitPrice\":26000"), snapshot);
  }

  /**
   * 历史快照（无 serverUnitPrice/roomFeeIncludesServer 字段）还原：服务单价按递增价反推，
   * **计费基数保持旧口径**（只按房型价），存量会话不被追溯涨价。
   */
  @Test
  void legacySnapshotRoundTripKeepsServerUnitPriceConsistentAndOldBillingBase() {
    KtvPricingPlan plan = storeLevelPlan().forRoomType("VIP", "VIP 大包", 20000L, 6000L);
    // 模拟 snapshotPlan 的 12 参还原（旧快照没有 serverUnitPrice/roomFeeIncludesServer）
    KtvPricingPlan restored = new KtvPricingPlan(plan.billingUnit(), plan.roomUnitPrice(),
        plan.defaultSessionMinutes(), plan.freeWaitMinutes(), plan.overtimeRate(), plan.incrementMinutes(),
        plan.roundingDirection(), plan.serverPricePerInc(), plan.unitPriceByRoomType(),
        plan.appliedRoomTypeCode(), plan.appliedRoomTypeName(), plan.roomTypePriceApplied());

    assertEquals(6000L, restored.serverUnitPrice(), "由 3000 分/30 分钟反推回 6000 分/小时");
    assertEquals(26000L, restored.combinedUnitPrice());
    assertFalse(restored.roomFeeIncludesServer(), "旧快照不得被追溯成新口径");
    assertEquals(10000L, restored.billableRoomPricePerIncrement(), "旧口径只按房型递增价计费");

    KtvPricingPlan fromSnapshot = restored.withSnapshotPricing(6000L, false);
    assertTrue(fromSnapshot.toSnapshotJson().contains("\"combinedUnitPrice\":26000"), fromSnapshot.toSnapshotJson());
    assertTrue(fromSnapshot.toSnapshotJson().contains("\"roomFeeIncludesServer\":false"), fromSnapshot.toSnapshotJson());
  }

  /** 新口径计费基数 = 房型递增价 + 服务递增价（超时倍率等规则仍作用在同一基数上）。 */
  @Test
  void newBillingBaseIsCombinedPricePerIncrement() {
    KtvPricingPlan plan = storeLevelPlan().forRoomType("VIP", "VIP 大包", 20000L, 6000L)
        .withRoomFeeIncludesServer(true);

    assertTrue(plan.roomFeeIncludesServer());
    assertEquals(10000L, plan.roomPricePerIncrement(), "房型价 20000/小时 → 每块 10000");
    assertEquals(3000L, plan.serverPricePerInc(), "服务价 6000/小时 → 每块 3000");
    assertEquals(13000L, plan.billableRoomPricePerIncrement(), "新口径基数 = 10000 + 3000");
  }

  /** 快照固化「包厢费已含 1 名标准服务人员」：免费服务人员数与基数口径都可事后复算。 */
  @Test
  void snapshotFreezesIncludedServerCount() {
    String withServer = storeLevelPlan().forRoomType("VIP", "VIP 大包", 20000L, 6000L)
        .withRoomFeeIncludesServer(true).toSnapshotJson();
    assertTrue(withServer.contains("\"roomFeeIncludesServer\":true"), withServer);
    assertTrue(withServer.contains("\"includedServerCount\":1"), withServer);

    String legacy = storeLevelPlan().toSnapshotJson();
    assertTrue(legacy.contains("\"roomFeeIncludesServer\":false"), legacy);
    assertTrue(legacy.contains("\"includedServerCount\":0"), legacy);
  }

  /** 回退门店价时快照同样标明「用了哪个房型、是否命中房型价」，账单可解释。 */
  @Test
  void snapshotMarksRoomTypeWithoutPriceAsFallback() {
    String snapshot = storeLevelPlan().forRoomType("SMALL", "小包", null, null).toSnapshotJson();

    assertTrue(snapshot.contains("\"roomUnitPrice\":3000"), snapshot);
    assertTrue(snapshot.contains("\"roomTypeCode\":\"SMALL\""), snapshot);
    assertTrue(snapshot.contains("\"roomTypePriceApplied\":false"), snapshot);
  }

  /** 未指定房型：快照里房型字段为 null（不是字符串 "null"），且按房型单价为空对象。 */
  @Test
  void snapshotWithoutRoomTypeWritesNullsAndEmptyMap() {
    String snapshot = storeLevelPlan().toSnapshotJson();

    assertTrue(snapshot.contains("\"roomTypeCode\":null"), snapshot);
    assertTrue(snapshot.contains("\"roomTypeName\":null"), snapshot);
    assertTrue(snapshot.contains("\"roomTypePriceApplied\":false"), snapshot);
    assertTrue(snapshot.contains("\"unitPriceByRoomType\":{}"), snapshot);
  }

  /** 方案按房型单价映射按键排序输出，保证同一方案快照文本稳定可比。 */
  @Test
  void snapshotSortsRoomTypePriceMap() {
    KtvPricingPlan plan = new KtvPricingPlan(KtvBillingUnit.HOUR, 3000L, 120, 0, new BigDecimal("1.5"), 30,
        KtvRoundingDirection.CONSUMER_FAVOR, 2500L, new java.util.LinkedHashMap<>(
        Map.of("VIP", 20000L, "MEDIUM", 12000L, "SMALL", 8000L)), null, null, false);

    String snapshot = plan.toSnapshotJson();

    // 字典序：MEDIUM < SMALL < VIP
    int medium = snapshot.indexOf("\"MEDIUM\"");
    int small = snapshot.indexOf("\"SMALL\"");
    int vip = snapshot.indexOf("\"VIP\"");
    assertTrue(medium < small && small < vip, snapshot);
  }

  /** 兼容构造（8 参）：不含按房型信息，行为与既有门店级方案一致。 */
  @Test
  void legacyConstructorKeepsStoreLevelSemantics() {
    KtvPricingPlan plan = storeLevelPlan();

    assertEquals(3000L, plan.roomUnitPrice());
    assertEquals(1500L, plan.roomPricePerIncrement());
    assertNull(plan.appliedRoomTypeCode());
    assertFalse(plan.roomTypePriceApplied());
  }
}

package com.gvchat.platform.order.domain.ktv.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gvchat.platform.order.domain.ktv.model.KtvBillingUnit;
import com.gvchat.platform.order.domain.ktv.model.KtvPricingPlan;
import com.gvchat.platform.order.domain.ktv.model.KtvRoundingDirection;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * 包厢计时费纯函数（F7 修复后的口径）：
 * 计费时长 D → 超时切分 D_in/D_over → 按「递增粒度 + 舍入方向」算块数 → 每递增粒度单价 × 块数。
 * 默认 CONSUMER_FAVOR：秒向下抹零、不足一块按一块（33 秒 = 0 元，不再按整小时收 100 元）。
 */
class KtvRoomFeeCalculatorTest {

  @Test
  void billableSeconds_clampsNegativeToZero() {
    // closed_at 早于 billing_start_at 或暂停时长超过时长 → 计费时长不为负。
    assertEquals(0L, KtvRoomFeeCalculator.billableSeconds(
        LocalDateTime.of(2026, 1, 1, 20, 0), LocalDateTime.of(2026, 1, 1, 19, 0), 0));
    assertEquals(0L, KtvRoomFeeCalculator.billableSeconds(
        LocalDateTime.of(2026, 1, 1, 20, 0), LocalDateTime.of(2026, 1, 1, 21, 0), 7200));
  }

  @Test
  void billableSeconds_deductsPausedSeconds() {
    assertEquals(1800L, KtvRoomFeeCalculator.billableSeconds(
        LocalDateTime.of(2026, 1, 1, 20, 0), LocalDateTime.of(2026, 1, 1, 21, 0), 1800));
  }

  @Test
  void billableSeconds_returnsZeroForNullBoundary() {
    assertEquals(0L, KtvRoomFeeCalculator.billableSeconds(null, LocalDateTime.now(), 0));
    assertEquals(0L, KtvRoomFeeCalculator.billableSeconds(LocalDateTime.now(), null, 0));
  }

  @Test
  void standardSeconds_usesReservedEndWhenPresent() {
    assertEquals(7200L, KtvRoomFeeCalculator.standardSeconds(
        LocalDateTime.of(2026, 1, 1, 20, 0), LocalDateTime.of(2026, 1, 1, 22, 0), 120));
  }

  @Test
  void standardSeconds_fallsBackToDefaultMinutes() {
    assertEquals(7200L, KtvRoomFeeCalculator.standardSeconds(
        LocalDateTime.of(2026, 1, 1, 20, 0), null, 120));
    assertEquals(0L, KtvRoomFeeCalculator.standardSeconds(null, null, -5));
  }

  /** 页面文案「¥100.00/小时 · 30 分钟递增」：每递增粒度单价 = 单位价 × 递增分钟 / 单位分钟。 */
  @Test
  void roomPricePerIncrement_convertsUnitPriceToIncrementPrice() {
    assertEquals(5000L, plan(KtvBillingUnit.HOUR, 10000L, 30, BigDecimal.ONE, KtvRoundingDirection.CONSUMER_FAVOR)
        .roomPricePerIncrement());
    assertEquals(10000L, plan(KtvBillingUnit.HOUR, 10000L, 60, BigDecimal.ONE, KtvRoundingDirection.CONSUMER_FAVOR)
        .roomPricePerIncrement());
    assertEquals(3000L, plan(KtvBillingUnit.HALF_HOUR, 3000L, 30, BigDecimal.ONE, KtvRoundingDirection.CONSUMER_FAVOR)
        .roomPricePerIncrement());
  }

  @Test
  void zeroDurationChargesZero() {
    KtvRoomFeeCalculator.Fee fee = calculate(0L, 7200L);
    assertEquals(0L, fee.amountMinor());
    assertEquals(0L, fee.units());
  }

  /** 边界：极短（33 秒 < 1 分钟）抹零后 0 块 0 元 —— 旧实现按整小时收 100 元的错就在这里。 */
  @Test
  void subMinuteDurationIsFreeUnderConsumerFavor() {
    assertEquals(0L, calculate(33L, 7200L).amountMinor());
    assertEquals(0L, calculate(59L, 7200L).units());
  }

  /** 不足一个递增块按一个块计（让利方向下仍收最小块）。 */
  @Test
  void underOneIncrementChargesOneBlock() {
    KtvRoomFeeCalculator.Fee fee = calculate(25 * 60L, 7200L);
    assertEquals(1L, fee.units());
    assertEquals(5000L, fee.amountMinor());
  }

  @Test
  void exactlyOneIncrementChargesOneBlock() {
    KtvRoomFeeCalculator.Fee fee = calculate(30 * 60L, 7200L);
    assertEquals(1L, fee.units());
    assertEquals(5000L, fee.amountMinor());
  }

  /** 跨多块进位：31 分钟 → 2 块，90 分钟 → 3 块。 */
  @Test
  void crossingIntoNextIncrementChargesNextBlock() {
    KtvRoomFeeCalculator.Fee thirtyOne = calculate(31 * 60L, 7200L);
    assertEquals(2L, thirtyOne.units());
    assertEquals(10000L, thirtyOne.amountMinor());

    KtvRoomFeeCalculator.Fee ninety = calculate(90 * 60L, 7200L);
    assertEquals(3L, ninety.units());
    assertEquals(15000L, ninety.amountMinor());
  }

  /** 超时部分按 overtime_rate 加价，只作用于超时块。 */
  @Test
  void overtimeRateAppliesOnlyToOverflowBlocks() {
    KtvPricingPlan plan = plan(KtvBillingUnit.HOUR, 10000L, 30, new BigDecimal("1.5"), KtvRoundingDirection.CONSUMER_FAVOR);
    // 标准 60 分钟、实计 90 分钟：时段内 2 块 × 5000 + 超时 1 块 × 5000 × 1.5 = 17500
    KtvRoomFeeCalculator.Fee fee = KtvRoomFeeCalculator.calculate(plan, 90 * 60L, 60 * 60L);
    assertEquals(3600L, fee.inSeconds());
    assertEquals(1800L, fee.overSeconds());
    assertEquals(3L, fee.units());
    assertEquals(5000L, fee.unitPriceMinor());
    assertEquals(17500L, fee.amountMinor());
  }

  /** 舍入方向必须真的影响金额（ROUND_UP 从严：不满 1 分钟按 1 分钟 → 至少 1 块）。 */
  @Test
  void roundingDirectionChangesRounding() {
    KtvPricingPlan roundUp = plan(KtvBillingUnit.HOUR, 10000L, 30, BigDecimal.ONE, KtvRoundingDirection.ROUND_UP);
    assertEquals(5000L, KtvRoomFeeCalculator.calculate(roundUp, 33L, 7200L).amountMinor());

    KtvPricingPlan floorBlock = plan(KtvBillingUnit.HOUR, 10000L, 30, BigDecimal.ONE, KtvRoundingDirection.FLOOR_BLOCK);
    // 59 分钟：floor(59/30) = 1 块
    assertEquals(1L, KtvRoomFeeCalculator.calculate(floorBlock, 59 * 60L, 7200L).units());
  }

  /** PACKAGE 为一口价套餐，不走时长递增计费。 */
  @Test
  void packagePlanIsNotDurationBillable() {
    KtvPricingPlan pkg = plan(KtvBillingUnit.PACKAGE, 12800L, 30, BigDecimal.ONE, KtvRoundingDirection.CONSUMER_FAVOR);
    assertFalse(KtvRoomFeeCalculator.durationBillable(pkg));
    assertEquals(0L, KtvRoomFeeCalculator.calculate(pkg, 3600L, 7200L).amountMinor());
    assertTrue(KtvRoomFeeCalculator.durationBillable(
        plan(KtvBillingUnit.HOUR, 10000L, 30, BigDecimal.ONE, KtvRoundingDirection.CONSUMER_FAVOR)));
  }

  /** 缺字段的历史计价快照（incrementMinutes=0 / roundingDirection=null）按默认值兜底，不能结台失败。 */
  @Test
  void missingIncrementOrDirectionFallsBackToDefaults() {
    KtvPricingPlan legacy = new KtvPricingPlan(KtvBillingUnit.HOUR, 10000L, 120, 0, null, 0, null, 5000L);
    assertEquals(30, legacy.effectiveIncrementMinutes());
    assertEquals(KtvRoundingDirection.CONSUMER_FAVOR, legacy.effectiveRoundingDirection());
    assertEquals(BigDecimal.ONE, legacy.effectiveOvertimeRate());
    assertEquals(5000L, KtvRoomFeeCalculator.calculate(legacy, 30 * 60L, 7200L).amountMinor());
  }

  /**
   * 新口径：包厢费基数 = 房型递增价 + 服务递增价（已含 1 名标准服务人员）。
   * 房型 ¥100/小时 → 每块 5000；服务 ¥50/小时 → 每块 2500；每块合计 7500。
   */
  @Test
  void combinedBaseChargesRoomPlusServerPerBlock() {
    KtvPricingPlan plan = combinedPlan(BigDecimal.ONE);

    assertEquals(7500L, plan.billableRoomPricePerIncrement());
    assertEquals(1L, KtvRoomFeeCalculator.calculate(plan, 30 * 60L, 7200L).units());
    assertEquals(7500L, KtvRoomFeeCalculator.calculate(plan, 30 * 60L, 7200L).amountMinor());
    // 90 分钟 = 3 块 → 22500（旧口径 15000）
    KtvRoomFeeCalculator.Fee ninety = KtvRoomFeeCalculator.calculate(plan, 90 * 60L, 7200L);
    assertEquals(3L, ninety.units());
    assertEquals(22500L, ninety.amountMinor());
    assertEquals(7500L, ninety.unitPriceMinor(), "明细 unit_price 也必须是合并基数（单价 × 数量 = 金额）");
  }

  /** 新口径下超时倍率仍作用在同一合并基数上（不新增「服务费不参与倍率」分支）。 */
  @Test
  void overtimeRateAppliesToCombinedBase() {
    KtvPricingPlan plan = combinedPlan(new BigDecimal("1.5"));
    // 标准 60 分钟、实计 90 分钟：时段内 2 块 × 7500 + 超时 1 块 × 7500 × 1.5 = 15000 + 11250 = 26250
    KtvRoomFeeCalculator.Fee fee = KtvRoomFeeCalculator.calculate(plan, 90 * 60L, 60 * 60L);

    assertEquals(3L, fee.units());
    assertEquals(7500L, fee.unitPriceMinor());
    assertEquals(26250L, fee.amountMinor());
  }

  /** 新口径同样遵守舍入规则：33 秒抹零 = 0 元，25 分钟不满一块按一块（7500）。 */
  @Test
  void combinedBaseKeepsRoundingRules() {
    KtvPricingPlan plan = combinedPlan(BigDecimal.ONE);

    assertEquals(0L, KtvRoomFeeCalculator.calculate(plan, 33L, 7200L).amountMinor());
    assertEquals(7500L, KtvRoomFeeCalculator.calculate(plan, 25 * 60L, 7200L).amountMinor());
    // 跨计费单位取整：91 分钟 → 4 块 × 7500
    assertEquals(4L, KtvRoomFeeCalculator.calculate(plan, 91 * 60L, 7200L).units());
    assertEquals(30000L, KtvRoomFeeCalculator.calculate(plan, 91 * 60L, 7200L).amountMinor());
  }

  /** 存量会话（旧快照/旧口径）继续只按房型价计，不被追溯涨价。 */
  @Test
  void legacyPlanWithoutCombinedBaseIsNotRetroCharged() {
    KtvPricingPlan legacy = plan(KtvBillingUnit.HOUR, 10000L, 30, BigDecimal.ONE, KtvRoundingDirection.CONSUMER_FAVOR);

    assertFalse(legacy.roomFeeIncludesServer());
    assertEquals(5000L, legacy.billableRoomPricePerIncrement());
    assertEquals(15000L, KtvRoomFeeCalculator.calculate(legacy, 90 * 60L, 7200L).amountMinor());
  }

  private static KtvRoomFeeCalculator.Fee calculate(long billableSeconds, long standardSeconds) {
    return KtvRoomFeeCalculator.calculate(
        plan(KtvBillingUnit.HOUR, 10000L, 30, BigDecimal.ONE, KtvRoundingDirection.CONSUMER_FAVOR),
        billableSeconds, standardSeconds);
  }

  /** 新口径方案：包厢费基数已含 1 名标准服务人员（房型 10000 + 服务 5000 / 小时，30 分钟递增）。 */
  private static KtvPricingPlan combinedPlan(BigDecimal overtimeRate) {
    // 服务 5000 分/小时 + 30 分钟递增 → 每块 2500；房型 10000 分/小时 → 每块 5000；合计每块 7500。
    return new KtvPricingPlan(KtvBillingUnit.HOUR, 10000L, 120, 0, overtimeRate, 30,
        KtvRoundingDirection.CONSUMER_FAVOR, 2500L).withRoomFeeIncludesServer(true);
  }

  private static KtvPricingPlan plan(KtvBillingUnit unit, long roomUnitPrice, int incrementMinutes,
                                     BigDecimal overtimeRate, KtvRoundingDirection direction) {
    return new KtvPricingPlan(unit, roomUnitPrice, 120, 0, overtimeRate, incrementMinutes, direction, 5000L);
  }
}

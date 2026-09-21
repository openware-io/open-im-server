package com.gvchat.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** KTV 服务人员单位时间计费公式：M=floor(D/60)，n=ceil(M/inc)，费=price_per_inc×n。 */
class UnitTimeFeeCalculatorTest {
  private static final int INC = 30;
  private static final long PRICE = 5000L;

  @Test
  void zeroDurationChargesZero() {
    assertEquals(0L, UnitTimeFeeCalculator.calculateTotal(0, INC, PRICE));
  }

  @Test
  void underThirtyMinutesRoundsUpToOneBlock() {
    // 10 分钟 → M=10 → n=ceil(10/30)=1
    assertEquals(PRICE, UnitTimeFeeCalculator.calculateTotal(600, INC, PRICE));
  }

  @Test
  void exactlyThirtyMinutesChargesOneBlock() {
    assertEquals(PRICE, UnitTimeFeeCalculator.calculateTotal(1800, INC, PRICE));
  }

  @Test
  void thirtyOneMinutesChargesTwoBlocks() {
    // 31 分钟 → M=31 → n=ceil(31/30)=2
    assertEquals(PRICE * 2, UnitTimeFeeCalculator.calculateTotal(1860, INC, PRICE));
  }

  @Test
  void exactlySixtyMinutesChargesTwoBlocks() {
    assertEquals(PRICE * 2, UnitTimeFeeCalculator.calculateTotal(3600, INC, PRICE));
  }

  @Test
  void nonPositiveIncrementFallsBackToThirty() {
    assertEquals(PRICE * 2, UnitTimeFeeCalculator.calculateTotal(3600, 0, PRICE));
    assertEquals(PRICE * 2, UnitTimeFeeCalculator.calculateTotal(3600, -5, PRICE));
  }

  @Test
  void minutesAreFloored() {
    assertEquals(0, UnitTimeFeeCalculator.calculateMinutes(59));
    assertEquals(59, UnitTimeFeeCalculator.calculateMinutes(3599));
    assertEquals(60, UnitTimeFeeCalculator.calculateMinutes(3600));
  }

  /** 边界：极短时长（33 秒 < 1 分钟）抹零后 0 块 0 元（让利消费者），不会按最小块多收。 */
  @Test
  void subMinuteDurationIsFree() {
    assertEquals(0, UnitTimeFeeCalculator.calculateBlocks(33, INC));
    assertEquals(0L, UnitTimeFeeCalculator.calculateTotal(33, INC, PRICE));
  }

  /** 舍入方向真的参与计费：ROUND_UP 从严时不满 1 分钟按 1 分钟算（= 1 块）。 */
  @Test
  void roundingDirectionIsApplied() {
    assertEquals(PRICE, UnitTimeFeeCalculator.calculateTotal(
        33, INC, PRICE, com.gvchat.platform.order.domain.ktv.model.KtvRoundingDirection.ROUND_UP));
    assertEquals(0L, UnitTimeFeeCalculator.calculateTotal(
        33, INC, PRICE, com.gvchat.platform.order.domain.ktv.model.KtvRoundingDirection.CONSUMER_FAVOR));
  }
}

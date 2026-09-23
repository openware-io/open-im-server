package io.openware.common.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.common.payment.infra.persistence.mapper.DailyClosingMapper;
import io.openware.common.payment.infra.persistence.mapper.PayCollectMapper;
import io.openware.common.payment.infra.persistence.mapper.PayIntentMapper;
import io.openware.common.payment.infra.persistence.mapper.RefundMapper;
import io.openware.common.payment.infra.persistence.mapper.ShiftMapper;
import io.openware.common.payment.infra.persistence.po.DailyClosingPo;
import io.openware.common.payment.infra.persistence.po.PayIntentPo;
import io.openware.common.payment.infra.persistence.po.ShiftPo;
import io.openware.infrastructure.audit.AuditClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/** 收银班次 + 日结：开班/交班现金对账（expected_cash 累计 + difference_amount）/日结提交与按币种汇总。 */
class CashierApplicationServiceTest {

  // 单位口径（冻结，勿误读）：openingCash/actualCash 与 pay_intent.amount 一律为**最小货币单位**
  // （CNY 分 / USD cent）的整数，不做分/元换算；本夹具全部为分量级：100 = 1.00 元、600 = 6.00 元。

  private final ShiftMapper shiftMapper = mock(ShiftMapper.class);
  private final DailyClosingMapper dailyClosingMapper = mock(DailyClosingMapper.class);
  private final PayIntentMapper payIntentMapper = mock(PayIntentMapper.class);
  private final RefundMapper refundMapper = mock(RefundMapper.class);
  private final PayCollectMapper payCollectMapper = mock(PayCollectMapper.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final CashierApplicationService service = new CashierApplicationService(shiftMapper,
      dailyClosingMapper, payIntentMapper, refundMapper, payCollectMapper, auditClient);

  @Test
  void openShift_createsOpenShiftWithZeroExpectedCash() {
    // 100 分（=1.00 元）开班备付金；分与分同量级，不做任何元/分换算。
    ShiftDto result = service.openShift(1L, 2L, 3L, 4L, new BigDecimal("100"));

    assertEquals("OPEN", result.status());
    assertEquals(0, new BigDecimal("100").compareTo(result.openingCash()));
    assertEquals(0, BigDecimal.ZERO.compareTo(result.expectedCash()));
    // 开班即固化币种快照：租户未配置币种时缺省 USD（16_CURRENCY_CONVENTIONS §1/§5）。
    assertEquals("USD", result.currencyCode());
    verify(shiftMapper).insert(any(ShiftPo.class));
  }

  @Test
  void openShift_treatsNullAndZeroOpeningCashAsZero() {
    ShiftDto fromNull = service.openShift(1L, 2L, 3L, 4L, null);
    assertEquals(0, BigDecimal.ZERO.compareTo(fromNull.openingCash()));

    ShiftDto fromZero = service.openShift(1L, 2L, 3L, 4L, BigDecimal.ZERO);
    assertEquals(0, BigDecimal.ZERO.compareTo(fromZero.openingCash()));
  }

  @Test
  void openShift_acceptsEquivalentIntegerNotation() {
    // 100 分写成 "100.00" 仍是同一个整数分（无小数部分），不属「非法分」。
    ShiftDto result = service.openShift(1L, 2L, 3L, 4L, new BigDecimal("100.00"));

    assertEquals(0, new BigDecimal("100").compareTo(result.openingCash()));
  }

  @Test
  void openShift_rejectsNegativeOpeningCash() {
    ApiException ex = assertThrows(ApiException.class,
        () -> service.openShift(1L, 2L, 3L, 4L, new BigDecimal("-1")));

    assertEquals(400, ex.getStatus());
    assertEquals("SHIFT_CASH_INVALID", ex.getCode());
    assertEquals("开班现金必须是不小于 0 的最小货币单位整数", ex.getMessage());
  }

  @Test
  void openShift_rejectsFractionalMinorUnitOpeningCash() {
    ApiException ex = assertThrows(ApiException.class,
        () -> service.openShift(1L, 2L, 3L, 4L, new BigDecimal("100.5")));

    assertEquals(400, ex.getStatus());
    assertEquals("SHIFT_CASH_INVALID", ex.getCode());
  }

  @Test
  void closeShift_accumulatesCashReceiptsIntoExpectedCashAndYieldsZeroDifference() {
    ShiftPo shift = shift("OPEN");
    shift.setOpeningCash(new BigDecimal("100"));
    shift.setOpenedAt(LocalDateTime.of(2025, 1, 1, 8, 0));
    when(shiftMapper.selectById(shift.getId())).thenReturn(shift);
    when(payIntentMapper.sumCashSucceeded(any(), any(), any(), any(), any()))
        .thenReturn(new BigDecimal("500"));

    // 600 分 = 5.00 元现金实收，与 openingCash(100 分) + 现金收款(500 分) 同单位相加。
    ShiftDto result = service.closeShift(shift.getId(), new BigDecimal("600"), "现金全额收款无差异");

    assertEquals("CLOSED", result.status());
    assertEquals(0, new BigDecimal("600").compareTo(result.actualCash()));
    assertEquals(0, new BigDecimal("600").compareTo(result.expectedCash()));
    assertEquals(0, BigDecimal.ZERO.compareTo(result.differenceAmount()));
    // 历史班次币种为空 → 归一为 USD 后再按币种汇总现金（不同币种不得相加）
    assertEquals("USD", result.currencyCode());
    verify(payIntentMapper).sumCashSucceeded(1L, 2L, "USD", shift.getOpenedAt(), shift.getClosedAt());
    verify(shiftMapper).updateById(shift);
  }

  @Test
  void closeShift_computesDifferenceWhenActualCashDiffers() {
    ShiftPo shift = shift("OPEN");
    shift.setOpeningCash(new BigDecimal("100"));
    shift.setOpenedAt(LocalDateTime.of(2025, 1, 1, 8, 0));
    when(shiftMapper.selectById(shift.getId())).thenReturn(shift);
    when(payIntentMapper.sumCashSucceeded(any(), any(), any(), any(), any()))
        .thenReturn(new BigDecimal("500"));

    ShiftDto result = service.closeShift(shift.getId(), new BigDecimal("620"), "备注");

    assertEquals("CLOSED", result.status());
    assertEquals(0, new BigDecimal("620").compareTo(result.actualCash()));
    assertEquals(0, new BigDecimal("600").compareTo(result.expectedCash()));
    assertEquals(0, new BigDecimal("20").compareTo(result.differenceAmount()));
    verify(shiftMapper).updateById(shift);
  }

  @Test
  void closeShift_treatsMissingOpeningCashAsZero() {
    ShiftPo shift = shift("OPEN");
    shift.setOpenedAt(LocalDateTime.of(2025, 1, 1, 8, 0));
    when(shiftMapper.selectById(shift.getId())).thenReturn(shift);
    when(payIntentMapper.sumCashSucceeded(any(), any(), any(), any(), any()))
        .thenReturn(new BigDecimal("500"));

    ShiftDto result = service.closeShift(shift.getId(), new BigDecimal("500"), "备注");

    assertEquals(0, BigDecimal.ZERO.compareTo(result.differenceAmount()));
  }

  @Test
  void closeShift_treatsNullActualCashAsZero() {
    ShiftPo shift = shift("OPEN");
    shift.setOpeningCash(new BigDecimal("100"));
    shift.setOpenedAt(LocalDateTime.of(2025, 1, 1, 8, 0));
    when(shiftMapper.selectById(shift.getId())).thenReturn(shift);
    when(payIntentMapper.sumCashSucceeded(any(), any(), any(), any(), any()))
        .thenReturn(new BigDecimal("500"));

    ShiftDto result = service.closeShift(shift.getId(), null, "未实盘");

    assertEquals(0, BigDecimal.ZERO.compareTo(result.actualCash()));
    assertEquals(0, new BigDecimal("-600").compareTo(result.differenceAmount()));
  }

  @Test
  void closeShift_rejectsNegativeActualCash() {
    ApiException ex = assertThrows(ApiException.class,
        () -> service.closeShift(1L, new BigDecimal("-0.01"), "备注"));

    assertEquals(400, ex.getStatus());
    assertEquals("SHIFT_CASH_INVALID", ex.getCode());
    assertEquals("交班现金必须是不小于 0 的最小货币单位整数", ex.getMessage());
  }

  @Test
  void closeShift_rejectsFractionalMinorUnitActualCash() {
    ApiException ex = assertThrows(ApiException.class,
        () -> service.closeShift(1L, new BigDecimal("600.5"), "备注"));

    assertEquals(400, ex.getStatus());
    assertEquals("SHIFT_CASH_INVALID", ex.getCode());
  }

  @Test
  void closeShift_rejectsWhenNotOpen() {
    when(shiftMapper.selectById(1L)).thenReturn(shift("CLOSED"));

    assertThrows(IllegalStateException.class,
        () -> service.closeShift(1L, new BigDecimal("120"), "备注"));
  }

  @Test
  void submitDailyClosing_createsSubmittedClosing() {
    LocalDate date = LocalDate.of(2025, 1, 1);

    DailyClosingDto result = service.submitDailyClosing(1L, 2L, date, 7L);

    assertEquals("SUBMITTED", result.status());
    assertEquals(date, result.businessDate());
    assertEquals(7L, result.submittedBy());
    // 日结按币种出具：未配置租户币种时缺省 USD（16_CURRENCY_CONVENTIONS §1/§5）。
    assertEquals("USD", result.currencyCode());
    ArgumentCaptor<DailyClosingPo> captor = ArgumentCaptor.forClass(DailyClosingPo.class);
    verify(dailyClosingMapper).insert(captor.capture());
    assertEquals(1L, captor.getValue().getTenantId());
  }

  // ---------------------------------------------------------------------------------------------
  // 日结汇总（pay_daily_closing.summary_json）：按币种分组落库，金额一律最小货币单位整数。
  // 金额口径（冻结）：本夹具全部为分量级——5000 = 50.00 元、700 = 7.00 元。
  // ---------------------------------------------------------------------------------------------

  @Test
  void submitDailyClosing_singleCurrency_writesGroupedSummaryWithAmounts() {
    LocalDate date = LocalDate.of(2025, 1, 1);
    when(payIntentMapper.selectList(any())).thenReturn(List.of(
        intent(11L, "CNY", "CASH", "5000"), intent(12L, "CNY", "ALIPAY", "3000")));
    when(shiftMapper.selectList(any())).thenReturn(List.of(shiftWithCurrency(21L, "CNY", "-100")));
    when(refundMapper.sumRefundedByCurrency(any(), any(), any(), any()))
        .thenReturn(List.of(refund("CNY", 1L, "2000")));

    DailyClosingDto result = service.submitDailyClosing(1L, 2L, date, 7L);

    DailyClosingSummary summary = result.summary();
    assertNotNull(summary);
    assertEquals("2025-01-01", summary.businessDate());
    assertEquals(2L, summary.storeId());
    // 单币种：顶层直接给出该币种，mixedCurrency=false
    assertEquals("CNY", summary.currencyCode());
    assertFalse(summary.mixedCurrency());
    assertEquals("CNY", result.currencyCode());
    assertEquals(1, summary.currencies().size());
    DailyClosingSummary.CurrencyLine line = summary.currencies().get(0);
    assertEquals("CNY", line.currencyCode());
    assertEquals(2L, line.collectionCount());
    assertEquals(8000L, line.collectedAmount());
    assertEquals(1L, line.cashCount());
    assertEquals(5000L, line.cashAmount());
    assertEquals(1L, line.refundCount());
    assertEquals(2000L, line.refundAmount());
    assertEquals(1L, line.shiftCount());
    assertEquals(-100L, line.shiftDifferenceAmount());
    assertEquals(List.of(
        new DailyClosingSummary.ProviderLine("ALIPAY", 1L, 3000L),
        new DailyClosingSummary.ProviderLine("CASH", 1L, 5000L)), line.providers());

    // 落库形态：结构固定、字段名与既有接口风格一致（*Amount 为最小货币单位整数）
    ArgumentCaptor<DailyClosingPo> captor = ArgumentCaptor.forClass(DailyClosingPo.class);
    verify(dailyClosingMapper).insert(captor.capture());
    assertEquals("CNY", captor.getValue().getCurrencyCode());
    assertEquals("{\"businessDate\":\"2025-01-01\",\"storeId\":2,\"currencyCode\":\"CNY\",\"mixedCurrency\":false,"
        + "\"currencies\":[{\"currencyCode\":\"CNY\",\"collectionCount\":2,\"collectedAmount\":8000,\"cashCount\":1,"
        + "\"cashAmount\":5000,\"refundCount\":1,\"refundAmount\":2000,\"shiftCount\":1,\"shiftDifferenceAmount\":-100,"
        + "\"providers\":[{\"provider\":\"ALIPAY\",\"count\":1,\"amount\":3000},"
        + "{\"provider\":\"CASH\",\"count\":1,\"amount\":5000}]}]}", captor.getValue().getSummaryJson());
    assertEquals(summary, DailyClosingSummary.parse(captor.getValue().getSummaryJson()));
  }

  @Test
  void submitDailyClosing_multipleCurrencies_groupsByCurrencyAndMarksMixed() {
    LocalDate date = LocalDate.of(2025, 1, 2);
    when(payIntentMapper.selectList(any())).thenReturn(List.of(
        intent(11L, "CNY", "CASH", "5000"), intent(12L, "USD", "CASH", "700"),
        intent(13L, "USD", "WECHAT", "300")));
    when(shiftMapper.selectList(any())).thenReturn(List.of(shiftWithCurrency(21L, "CNY", "100")));
    when(refundMapper.sumRefundedByCurrency(any(), any(), any(), any())).thenReturn(List.of());

    DailyClosingDto result = service.submitDailyClosing(1L, 2L, date, 7L);

    DailyClosingSummary summary = result.summary();
    assertNotNull(summary);
    // 混币种：顶层不给币种（不得给出跨币种合计），由 mixedCurrency 显式标注
    assertNull(summary.currencyCode());
    assertTrue(summary.mixedCurrency());
    assertEquals(List.of("CNY", "USD"),
        summary.currencies().stream().map(DailyClosingSummary.CurrencyLine::currencyCode).toList());

    DailyClosingSummary.CurrencyLine cny = summary.currencies().get(0);
    assertEquals(1L, cny.collectionCount());
    assertEquals(5000L, cny.collectedAmount());
    assertEquals(5000L, cny.cashAmount());
    assertEquals(100L, cny.shiftDifferenceAmount());

    DailyClosingSummary.CurrencyLine usd = summary.currencies().get(1);
    assertEquals(2L, usd.collectionCount());
    // USD 组只能是 700 + 300：把 CNY 的 5000 加进来会得到没有业务含义的跨币种合计
    assertEquals(1000L, usd.collectedAmount());
    assertEquals(1L, usd.cashCount());
    assertEquals(700L, usd.cashAmount());
    assertEquals(0L, usd.shiftDifferenceAmount());
    assertEquals(0L, usd.refundAmount());

    ArgumentCaptor<DailyClosingPo> captor = ArgumentCaptor.forClass(DailyClosingPo.class);
    verify(dailyClosingMapper).insert(captor.capture());
    // 混币种时列上保留当时租户币种（缺省 USD），混币种事实由 summary.mixedCurrency 表达
    assertEquals("USD", captor.getValue().getCurrencyCode());
  }

  @Test
  void submitDailyClosing_emptyBusinessDate_writesZeroSummaryWithoutNullFields() {
    LocalDate date = LocalDate.of(2025, 1, 3);

    DailyClosingDto result = service.submitDailyClosing(1L, 2L, date, 7L);

    DailyClosingSummary summary = result.summary();
    assertNotNull(summary);
    assertEquals("USD", summary.currencyCode());
    assertFalse(summary.mixedCurrency());
    assertEquals(1, summary.currencies().size());
    DailyClosingSummary.CurrencyLine line = summary.currencies().get(0);
    assertEquals("USD", line.currencyCode());
    assertEquals(0L, line.collectionCount());
    assertEquals(0L, line.collectedAmount());
    assertEquals(0L, line.cashCount());
    assertEquals(0L, line.cashAmount());
    assertEquals(0L, line.refundCount());
    assertEquals(0L, line.refundAmount());
    assertEquals(0L, line.shiftCount());
    assertEquals(0L, line.shiftDifferenceAmount());
    assertTrue(line.providers().isEmpty());

    ArgumentCaptor<DailyClosingPo> captor = ArgumentCaptor.forClass(DailyClosingPo.class);
    verify(dailyClosingMapper).insert(captor.capture());
    // 空营业日也必须落完整结构：不写 null、不抛错
    assertNotNull(captor.getValue().getSummaryJson());
    assertFalse(captor.getValue().getSummaryJson().contains("null"),
        captor.getValue().getSummaryJson());
  }

  @Test
  void submitDailyClosing_shiftDifferences_sumOnlySameCurrencyShifts() {
    // 同一天两个 CNY 班次 + 一个 USD 班次：长短款必须按班次币种快照分别汇总，
    // 把 100 + (-40) + (-50) 相加会得出一个没有业务含义的「长短款」。
    when(payIntentMapper.selectList(any())).thenReturn(List.of());
    when(shiftMapper.selectList(any())).thenReturn(List.of(
        shiftWithCurrency(21L, "CNY", "100"), shiftWithCurrency(22L, "CNY", "-40"),
        shiftWithCurrency(23L, "USD", "-50")));
    when(refundMapper.sumRefundedByCurrency(any(), any(), any(), any())).thenReturn(List.of());

    DailyClosingSummary summary = service.submitDailyClosing(1L, 2L, LocalDate.of(2025, 1, 4), 7L).summary();

    assertNotNull(summary);
    assertTrue(summary.mixedCurrency());
    assertEquals(2, summary.currencies().size());
    DailyClosingSummary.CurrencyLine cny = summary.currencies().get(0);
    assertEquals("CNY", cny.currencyCode());
    assertEquals(2L, cny.shiftCount());
    assertEquals(60L, cny.shiftDifferenceAmount());
    assertEquals(0L, cny.collectionCount());
    assertEquals(0L, cny.collectedAmount());
    DailyClosingSummary.CurrencyLine usd = summary.currencies().get(1);
    assertEquals("USD", usd.currencyCode());
    assertEquals(1L, usd.shiftCount());
    assertEquals(-50L, usd.shiftDifferenceAmount());
  }

  @Test
  void submitDailyClosing_repeatedSubmission_recomputesSameSummaryAndKeepsUniqueKeyRejection() {
    LocalDate date = LocalDate.of(2025, 1, 5);
    when(payIntentMapper.selectList(any())).thenReturn(List.of(intent(11L, "CNY", "CASH", "5000")));
    when(shiftMapper.selectList(any())).thenReturn(List.of());
    when(refundMapper.sumRefundedByCurrency(any(), any(), any(), any())).thenReturn(List.of());

    DailyClosingDto first = service.submitDailyClosing(1L, 2L, date, 7L);
    ArgumentCaptor<DailyClosingPo> firstInsert = ArgumentCaptor.forClass(DailyClosingPo.class);
    verify(dailyClosingMapper).insert(firstInsert.capture());

    // 第二次提交：沿用既有实现——仍按当时数据重算汇总后 insert，由唯一键
    // uk_pay_daily_tenant_store_date 拒绝（本方法不吞异常、不改成覆盖更新）。
    List<DailyClosingPo> rejected = new ArrayList<>();
    doAnswer(invocation -> {
      rejected.add(invocation.getArgument(0));
      throw new DuplicateKeyException("uk_pay_daily_tenant_store_date");
    }).when(dailyClosingMapper).insert(any(DailyClosingPo.class));

    assertThrows(DuplicateKeyException.class, () -> service.submitDailyClosing(1L, 2L, date, 7L));

    assertEquals(1, rejected.size());
    assertEquals(firstInsert.getValue().getSummaryJson(), rejected.get(0).getSummaryJson());
    assertEquals(first.summary(), DailyClosingSummary.parse(rejected.get(0).getSummaryJson()));
    assertEquals(5000L, first.summary().currencies().get(0).collectedAmount());
  }

  @Test
  void submitDailyClosing_fractionalMinorUnitAmount_rejectsInsteadOfRounding() {
    // 5000.5 分是「非法分」：与交班现金同一冻结口径，拒绝而不是静默取整成 5000 或 5001。
    when(payIntentMapper.selectList(any())).thenReturn(List.of(intent(11L, "CNY", "CASH", "5000.5")));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.submitDailyClosing(1L, 2L, LocalDate.of(2025, 1, 6), 7L));

    assertEquals(400, ex.getStatus());
    assertEquals("DAILY_CLOSING_AMOUNT_INVALID", ex.getCode());
  }

  @Test
  void submitDailyClosing_rejectsNullBusinessDate() {
    ApiException ex = assertThrows(ApiException.class, () -> service.submitDailyClosing(1L, 2L, null, 7L));

    assertEquals(400, ex.getStatus());
    assertEquals("DAILY_CLOSING_DATE_REQUIRED", ex.getCode());
  }

  @Test
  void dailyClosingDto_toleratesLegacySummaryJson() {
    // 本改动之前提交的历史日结 summary_json 从不写入（或已被手工改坏）：列表接口降级为 null 汇总，不抛错。
    DailyClosingPo po = new DailyClosingPo();
    po.setId(9L);
    po.setStoreId(2L);
    po.setBusinessDate(LocalDate.of(2025, 1, 7));
    po.setCurrencyCode("USD");
    po.setSummaryJson("{\"businessDate\":");

    DailyClosingDto dto = DailyClosingDto.from(po);

    assertNull(dto.summary());
    assertEquals("USD", dto.currencyCode());
    assertEquals(LocalDate.of(2025, 1, 7), dto.businessDate());
  }

  // ---------------------------------------------------------------------------------------------
  // 储值币（WALLET）/积分（POINT）纳入日结：**支付工具**（不是货币）的独立分项，只对新增数据生效。
  // 真实 response_json 结构（CollectApplicationService.CollectResult 序列化，字段名稳定）：
  //   {"remainingAmount":0,"collectedByMethod":[{"method":"WALLET","amount":3000},{"method":"CASH","amount":5000}]}
  // 每条分腿只有 method + 一个数值 = **该腿抵扣的账单金额**（货币，最小货币单位）；
  // JSON 里**没有**独立的代币/积分数量字段，因此日结不输出数量、也不按比例反推。
  // ---------------------------------------------------------------------------------------------

  /** 现金 + 储值币抵扣：CASH 与 WALLET 各一行；WALLET 行是「抵扣的账单金额」，不并入现金合计。 */
  @Test
  void submitDailyClosing_includesWalletBillOffsetAsSeparateProviderLine() {
    LocalDate date = LocalDate.of(2025, 2, 1);
    when(payIntentMapper.selectList(any())).thenReturn(List.of(intent(11L, "CNY", "CASH", "5000")));
    when(payCollectMapper.selectConfirmedByStoreAndWindow(any(), any(), any(), any())).thenReturn(List.of(
        collect("CNY", "{\"remainingAmount\":0,\"collectedByMethod\":["
            + "{\"method\":\"WALLET\",\"amount\":3000},{\"method\":\"CASH\",\"amount\":5000}]}")));
    when(shiftMapper.selectList(any())).thenReturn(List.of());
    when(refundMapper.sumRefundedByCurrency(any(), any(), any(), any())).thenReturn(List.of());

    DailyClosingSummary summary = service.submitDailyClosing(1L, 2L, date, 7L).summary();

    assertNotNull(summary);
    assertEquals("CNY", summary.currencyCode());
    DailyClosingSummary.CurrencyLine cny = summary.currencies().get(0);
    // 现金口径不变：collectedAmount/cashAmount/笔数仍只等于 pay_intent（现金类渠道）
    assertEquals(5000L, cny.collectedAmount(), "储值币抵扣不并入 collectedAmount");
    assertEquals(5000L, cny.cashAmount(), "储值币抵扣不并入现金合计");
    assertEquals(1L, cny.collectionCount(), "储值币抵扣不增加 pay_intent 收款笔数");
    assertEquals(List.of(
        new DailyClosingSummary.ProviderLine("CASH", 1L, 5000L),
        new DailyClosingSummary.ProviderLine("WALLET", 1L, 3000L)), cny.providers());
    // 支付构成：现金类合计 + 储值币抵扣的账单金额 = 账单已付（最小货币单位整数）
    assertEquals(8000L, cny.collectedAmount() + walletOffset(cny));
  }

  /** 现金 + 积分抵扣：出现独立的 POINT 分项（真实分腿码是 POINT，不是 POINTS）。 */
  @Test
  void submitDailyClosing_includesPointBillOffsetAsSeparateProviderLine() {
    LocalDate date = LocalDate.of(2025, 2, 2);
    when(payIntentMapper.selectList(any())).thenReturn(List.of(intent(11L, "CNY", "CASH", "5000")));
    when(payCollectMapper.selectConfirmedByStoreAndWindow(any(), any(), any(), any())).thenReturn(List.of(
        collect("CNY", "{\"remainingAmount\":0,\"collectedByMethod\":["
            + "{\"method\":\"POINT\",\"amount\":2000},{\"method\":\"CASH\",\"amount\":5000}]}")));
    when(shiftMapper.selectList(any())).thenReturn(List.of());
    when(refundMapper.sumRefundedByCurrency(any(), any(), any(), any())).thenReturn(List.of());

    DailyClosingSummary summary = service.submitDailyClosing(1L, 2L, date, 7L).summary();

    assertNotNull(summary);
    DailyClosingSummary.CurrencyLine cny = summary.currencies().get(0);
    assertEquals(List.of(
        new DailyClosingSummary.ProviderLine("CASH", 1L, 5000L),
        new DailyClosingSummary.ProviderLine("POINT", 1L, 2000L)), cny.providers());
    assertEquals(5000L, cny.collectedAmount());
    assertEquals(7000L, cny.collectedAmount() + pointOffset(cny));
  }

  /**
   * 语义断言：WALLET/POINT 分项只承载**抵扣的账单金额**（货币），
   * 不产生任何「代币/积分数量」字段，也不带货币符号 —— 数量不在 response_json 里，故不输出。
   */
  @Test
  void submitDailyClosing_walletAndPointLinesCarryBillOffsetNotTokenCounts() {
    LocalDate date = LocalDate.of(2025, 2, 7);
    when(payIntentMapper.selectList(any())).thenReturn(List.of(intent(11L, "CNY", "CASH", "5000")));
    when(payCollectMapper.selectConfirmedByStoreAndWindow(any(), any(), any(), any())).thenReturn(List.of(
        collect("CNY", "{\"collectedByMethod\":[{\"method\":\"WALLET\",\"amount\":3000},"
            + "{\"method\":\"POINT\",\"amount\":2000}]}")));
    when(shiftMapper.selectList(any())).thenReturn(List.of());
    when(refundMapper.sumRefundedByCurrency(any(), any(), any(), any())).thenReturn(List.of());

    service.submitDailyClosing(1L, 2L, date, 7L);

    ArgumentCaptor<DailyClosingPo> captor = ArgumentCaptor.forClass(DailyClosingPo.class);
    verify(dailyClosingMapper).insert(captor.capture());
    String json = captor.getValue().getSummaryJson();
    // 没有代币/积分数量字段（命名不得为 *Amount/数量，也不得带币种）
    assertFalse(json.contains("Tokens"), json);
    assertFalse(json.contains("tokens"), json);
    assertFalse(json.contains("\"points\""), json);
    assertFalse(json.contains("TokenCount"), json);
    // 只有货币金额（pay_intent 的现金类 + 抵扣账单金额），无货币符号/千分位等展示态文本
    assertFalse(json.contains("$"), json);
    assertFalse(json.contains("¥"), json);
    assertFalse(json.contains("欢乐币"), json);
    assertFalse(json.contains("积分"), json);
    // 抵扣金额确实按币种分组落在 providers[] 里（作为货币参与支付构成）
    DailyClosingSummary summary = DailyClosingSummary.parse(json);
    assertNotNull(summary);
    assertEquals(2L, summary.currencies().get(0).providers().stream()
        .filter(line -> !"CASH".equals(line.provider())).count());
  }

  /** 多币种：储值币/积分的**抵扣账单金额**各归自己币种组，绝不跨币种求和。 */
  @Test
  void submitDailyClosing_groupsWalletAndPointOffsetsByTheirOwnCurrency() {
    LocalDate date = LocalDate.of(2025, 2, 3);
    when(payIntentMapper.selectList(any())).thenReturn(List.of());
    when(payCollectMapper.selectConfirmedByStoreAndWindow(any(), any(), any(), any())).thenReturn(List.of(
        collect("CNY", "{\"collectedByMethod\":[{\"method\":\"WALLET\",\"amount\":3000}]}"),
        collect("USD", "{\"collectedByMethod\":[{\"method\":\"POINT\",\"amount\":700}]}")));
    when(shiftMapper.selectList(any())).thenReturn(List.of());
    when(refundMapper.sumRefundedByCurrency(any(), any(), any(), any())).thenReturn(List.of());

    DailyClosingSummary summary = service.submitDailyClosing(1L, 2L, date, 7L).summary();

    assertNotNull(summary);
    assertTrue(summary.mixedCurrency());
    assertNull(summary.currencyCode(), "混币种时顶层不得给出跨币种合计币种");
    assertEquals(List.of("CNY", "USD"),
        summary.currencies().stream().map(DailyClosingSummary.CurrencyLine::currencyCode).toList());
    assertEquals(List.of(new DailyClosingSummary.ProviderLine("WALLET", 1L, 3000L)),
        summary.currencies().get(0).providers());
    assertEquals(List.of(new DailyClosingSummary.ProviderLine("POINT", 1L, 700L)),
        summary.currencies().get(1).providers());
    assertEquals(0L, summary.currencies().get(0).collectedAmount());
    assertEquals(0L, summary.currencies().get(1).collectedAmount());
  }

  /** response_json 为空/损坏/缺字段：跳过并计 0，绝不抛错、绝不写 null 分项。 */
  @Test
  void submitDailyClosing_toleratesEmptyOrBrokenCollectResponseJson() {
    LocalDate date = LocalDate.of(2025, 2, 4);
    when(payIntentMapper.selectList(any())).thenReturn(List.of());
    when(payCollectMapper.selectConfirmedByStoreAndWindow(any(), any(), any(), any())).thenReturn(List.of(
        collect("CNY", null),
        collect(null, "   "),
        collect("CNY", "{\"businessDate\":"),
        collect("CNY", "{}"),
        collect("CNY", "{\"collectedByMethod\":\"WALLET\"}"),
        collect("CNY", "{\"collectedByMethod\":[{\"amount\":3000},{\"method\":\"WALLET\"},"
            + "{\"method\":\"WALLET\",\"amount\":\"x\"},{\"method\":\"WALLET\",\"amount\":0}]}"),
        collect("CNY", "{\"collectedByMethod\":[{\"method\":\"WALLET\",\"amount\":3000}]}")));
    when(shiftMapper.selectList(any())).thenReturn(List.of());
    when(refundMapper.sumRefundedByCurrency(any(), any(), any(), any())).thenReturn(List.of());

    DailyClosingSummary summary = service.submitDailyClosing(1L, 2L, date, 7L).summary();

    assertNotNull(summary);
    DailyClosingSummary.CurrencyLine cny = summary.currencies().get(0);
    // 只有最后一条（结构完整且金额 > 0）被计入；其余全部跳过、不抛错。
    assertEquals(List.of(new DailyClosingSummary.ProviderLine("WALLET", 1L, 3000L)), cny.providers());
    assertEquals(0L, cny.collectedAmount());
  }

  /** 历史 summary_json（本改动之前不含储值/积分分项）容错解析不回归。 */
  @Test
  void dailyClosingSummary_parsesLegacyJsonWithoutWalletPointProviders() {
    String legacy = "{\"businessDate\":\"2025-01-01\",\"storeId\":2,\"currencyCode\":\"CNY\","
        + "\"mixedCurrency\":false,\"currencies\":[{\"currencyCode\":\"CNY\",\"collectionCount\":1,"
        + "\"collectedAmount\":5000,\"cashCount\":1,\"cashAmount\":5000,\"refundCount\":0,\"refundAmount\":0,"
        + "\"shiftCount\":0,\"shiftDifferenceAmount\":0,\"providers\":[{\"provider\":\"CASH\",\"count\":1,"
        + "\"amount\":5000}]}]}";

    DailyClosingSummary summary = DailyClosingSummary.parse(legacy);

    assertNotNull(summary);
    assertEquals(5000L, summary.currencies().get(0).collectedAmount());
    assertEquals(List.of(new DailyClosingSummary.ProviderLine("CASH", 1L, 5000L)),
        summary.currencies().get(0).providers());
  }

  // ---------------------------------------------------------------------------------------------
  // 领域留痕：/business/** 开班/交班/日结没有 BFF 兜底，成功与失败都必须由本服务留痕。
  // ---------------------------------------------------------------------------------------------

  @Test
  void openShift_writesSucceededAudit() {
    service.openShift(1L, 2L, 3L, 4L, new BigDecimal("100"));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("cashier.shift.open", record.action());
    assertEquals("开班", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    assertNull(record.errorCode());
  }

  @Test
  void openShift_writesFailureAuditWithExceptionClassName() {
    when(shiftMapper.insert(any(ShiftPo.class))).thenThrow(new IllegalStateException("db down"));

    assertThrows(IllegalStateException.class, () -> service.openShift(1L, 2L, 3L, 4L, new BigDecimal("100")));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("cashier.shift.open", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("IllegalStateException", record.errorCode());
    assertNull(record.idempotencyKey());
    // 失败详情不含现金金额。
    assertFalse(record.detailJson().contains("100"), record.detailJson());
  }

  @Test
  void closeShift_writesFailureAuditWhenShiftNotOpen() {
    when(shiftMapper.selectById(1L)).thenReturn(shift("CLOSED"));

    assertThrows(IllegalStateException.class, () -> service.closeShift(1L, new BigDecimal("120"), "备注"));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("cashier.shift.close", record.action());
    assertEquals("交班结账", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("IllegalStateException", record.errorCode());
    assertNull(record.idempotencyKey());
  }

  @Test
  void closeShift_writesSucceededAudit() {
    ShiftPo shift = shift("OPEN");
    shift.setOpeningCash(new BigDecimal("100"));
    shift.setOpenedAt(LocalDateTime.of(2025, 1, 1, 8, 0));
    when(shiftMapper.selectById(shift.getId())).thenReturn(shift);
    when(payIntentMapper.sumCashSucceeded(any(), any(), any(), any(), any()))
        .thenReturn(new BigDecimal("500"));

    service.closeShift(shift.getId(), new BigDecimal("600"), "现金全额收款无差异");

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("cashier.shift.close", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
  }

  @Test
  void submitDailyClosing_writesSucceededAudit() {
    service.submitDailyClosing(1L, 2L, LocalDate.of(2025, 2, 5), 7L);

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("cashier.daily_closing.close", record.action());
    assertEquals("营业日结", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    assertNull(record.errorCode());
  }

  @Test
  void submitDailyClosing_writesFailureAuditOnDuplicateSubmission() {
    LocalDate date = LocalDate.of(2025, 2, 6);
    when(dailyClosingMapper.insert(any(DailyClosingPo.class)))
        .thenThrow(new DuplicateKeyException("uk_pay_daily_tenant_store_date"));

    assertThrows(DuplicateKeyException.class, () -> service.submitDailyClosing(1L, 2L, date, 7L));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("cashier.daily_closing.close", record.action());
    assertEquals("营业日结", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("DuplicateKeyException", record.errorCode());
    assertNull(record.idempotencyKey());
  }

  private static PayCollectMapper.ConfirmedCollectRow collect(String currencyCode, String responseJson) {
    PayCollectMapper.ConfirmedCollectRow row = new PayCollectMapper.ConfirmedCollectRow();
    row.setCurrencyCode(currencyCode);
    row.setResponseJson(responseJson);
    return row;
  }

  /** WALLET 分项的抵扣账单金额（无该分项时 0）。 */
  private static long walletOffset(DailyClosingSummary.CurrencyLine line) {
    return providerAmount(line, "WALLET");
  }

  /** POINT 分项的抵扣账单金额（无该分项时 0）。 */
  private static long pointOffset(DailyClosingSummary.CurrencyLine line) {
    return providerAmount(line, "POINT");
  }

  private static long providerAmount(DailyClosingSummary.CurrencyLine line, String provider) {
    return line.providers().stream().filter(item -> provider.equals(item.provider()))
        .mapToLong(DailyClosingSummary.ProviderLine::amount).sum();
  }

  private AuditClient.AuditRecord capturedAudit() {
    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    return captor.getValue();
  }

  private static PayIntentPo intent(Long id, String currencyCode, String provider, String amount) {
    PayIntentPo po = new PayIntentPo();
    po.setId(id);
    po.setCurrencyCode(currencyCode);
    po.setProvider(provider);
    po.setAmount(new BigDecimal(amount));
    po.setStatus("SUCCEEDED");
    return po;
  }

  private static ShiftPo shiftWithCurrency(Long id, String currencyCode, String differenceAmount) {
    ShiftPo po = shift("CLOSED");
    po.setId(id);
    po.setCurrencyCode(currencyCode);
    po.setDifferenceAmount(new BigDecimal(differenceAmount));
    return po;
  }

  private static RefundMapper.RefundCurrencyAggregate refund(String currencyCode, long count, String amount) {
    RefundMapper.RefundCurrencyAggregate row = new RefundMapper.RefundCurrencyAggregate();
    row.setCurrencyCode(currencyCode);
    row.setRefundCount(count);
    row.setRefundAmount(new BigDecimal(amount));
    return row;
  }

  private static ShiftPo shift(String status) {
    ShiftPo po = new ShiftPo();
    po.setId(1L);
    po.setTenantId(1L);
    po.setStoreId(2L);
    po.setStatus(status);
    return po;
  }
}

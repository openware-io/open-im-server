package io.openware.common.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.common.payment.domain.cashier.model.ConfirmedCollectLine;
import io.openware.common.payment.domain.cashier.model.RefundCurrencyAggregate;
import io.openware.common.payment.domain.cashier.repository.CashierReportRepository;
import io.openware.common.payment.domain.closing.model.DailyClosing;
import io.openware.common.payment.domain.closing.repository.DailyClosingRepository;
import io.openware.common.payment.domain.intent.model.PaymentIntent;
import io.openware.common.payment.domain.shift.model.Shift;
import io.openware.common.payment.domain.shift.repository.ShiftRepository;

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

  private final ShiftRepository shiftRepository = mock(ShiftRepository.class);
  private final DailyClosingRepository dailyClosingRepository = mock(DailyClosingRepository.class);
  private final CashierReportRepository reports = mock(CashierReportRepository.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final CashierApplicationService service = new CashierApplicationService(shiftRepository,
      dailyClosingRepository, reports, auditClient);

  /**
   * 端口的新增返回落库后的聚合（含回填主键），与真实适配器一致。
   *
   * <p>不桩设的话 Mockito 默认返回 null，服务在取回填主键时会 NPE——那是替身与真实适配器的差异，
   * 不是被测行为，所以统一在 setUp 里补齐；需要失败路径的用例再各自覆盖。
   */
  @org.junit.jupiter.api.BeforeEach
  void setUpInsertReturnsSavedAggregate() {
    when(shiftRepository.insert(any(Shift.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(dailyClosingRepository.insert(any(DailyClosing.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void openShift_createsOpenShiftWithZeroExpectedCash() {
    // 100 分（=1.00 元）开班备付金；分与分同量级，不做任何元/分换算。
    ShiftDto result = service.openShift(1L, 2L, 3L, 4L, new BigDecimal("100"));

    assertEquals("OPEN", result.status());
    assertEquals(0, new BigDecimal("100").compareTo(result.openingCash()));
    assertEquals(0, BigDecimal.ZERO.compareTo(result.expectedCash()));
    // 开班即固化币种快照：租户未配置币种时缺省 USD（16_CURRENCY_CONVENTIONS §1/§5）。
    assertEquals("USD", result.currencyCode());
    verify(shiftRepository).insert(any(Shift.class));
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
    Shift shift = shift("OPEN");
    shift = withOpeningCash(shift, "100");
    shift = withOpenedAt(shift, LocalDateTime.of(2025, 1, 1, 8, 0));
    when(shiftRepository.findById(shift.id())).thenReturn(java.util.Optional.of(shift));
    when(shiftRepository.updateClosing(any(Shift.class))).thenReturn(true);
    when(reports.sumCashSucceeded(any(), any(), any(), any(), any()))
        .thenReturn(new BigDecimal("500"));

    // 600 分 = 5.00 元现金实收，与 openingCash(100 分) + 现金收款(500 分) 同单位相加。
    ShiftDto result = service.closeShift(shift.id(), new BigDecimal("600"), "现金全额收款无差异");

    assertEquals("CLOSED", result.status());
    assertEquals(0, new BigDecimal("600").compareTo(result.actualCash()));
    assertEquals(0, new BigDecimal("600").compareTo(result.expectedCash()));
    assertEquals(0, BigDecimal.ZERO.compareTo(result.differenceAmount()));
    // 历史班次币种为空 → 归一为 USD 后再按币种汇总现金（不同币种不得相加）
    assertEquals("USD", result.currencyCode());
    // 现金汇总窗口：[opened_at, closed_at)，闭口由服务用 now 填实；这里只锁币种与门店维度。
    verify(reports).sumCashSucceeded(eq(1L), eq(2L), eq("USD"), eq(shift.openedAt()),
        any(LocalDateTime.class));
    verify(shiftRepository).updateClosing(any(Shift.class));
  }

  @Test
  void closeShift_computesDifferenceWhenActualCashDiffers() {
    Shift shift = withOpenedAt(withOpeningCash(shift("OPEN"), "100"), LocalDateTime.of(2025, 1, 1, 8, 0));
    when(shiftRepository.findById(shift.id())).thenReturn(java.util.Optional.of(shift));
    when(shiftRepository.updateClosing(any(Shift.class))).thenReturn(true);
    when(reports.sumCashSucceeded(any(), any(), any(), any(), any()))
        .thenReturn(new BigDecimal("500"));

    ShiftDto result = service.closeShift(shift.id(), new BigDecimal("620"), "备注");

    assertEquals("CLOSED", result.status());
    assertEquals(0, new BigDecimal("620").compareTo(result.actualCash()));
    assertEquals(0, new BigDecimal("600").compareTo(result.expectedCash()));
    assertEquals(0, new BigDecimal("20").compareTo(result.differenceAmount()));
    verify(shiftRepository).updateClosing(any(Shift.class));
  }

  @Test
  void closeShift_treatsMissingOpeningCashAsZero() {
    Shift shift = withOpenedAt(shift("OPEN"), LocalDateTime.of(2025, 1, 1, 8, 0));
    when(shiftRepository.findById(shift.id())).thenReturn(java.util.Optional.of(shift));
    when(shiftRepository.updateClosing(any(Shift.class))).thenReturn(true);
    when(reports.sumCashSucceeded(any(), any(), any(), any(), any()))
        .thenReturn(new BigDecimal("500"));

    ShiftDto result = service.closeShift(shift.id(), new BigDecimal("500"), "备注");

    assertEquals(0, BigDecimal.ZERO.compareTo(result.differenceAmount()));
  }

  @Test
  void closeShift_treatsNullActualCashAsZero() {
    Shift shift = withOpenedAt(withOpeningCash(shift("OPEN"), "100"), LocalDateTime.of(2025, 1, 1, 8, 0));
    when(shiftRepository.findById(shift.id())).thenReturn(java.util.Optional.of(shift));
    when(shiftRepository.updateClosing(any(Shift.class))).thenReturn(true);
    when(reports.sumCashSucceeded(any(), any(), any(), any(), any()))
        .thenReturn(new BigDecimal("500"));

    ShiftDto result = service.closeShift(shift.id(), null, "未实盘");

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
    when(shiftRepository.findById(1L)).thenReturn(java.util.Optional.ofNullable(shift("CLOSED")));

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
    ArgumentCaptor<DailyClosing> captor = ArgumentCaptor.forClass(DailyClosing.class);
    verify(dailyClosingRepository).insert(captor.capture());
    assertEquals(1L, captor.getValue().tenantId());
  }

  // ---------------------------------------------------------------------------------------------
  // 日结汇总（pay_daily_closing.summary_json）：按币种分组落库，金额一律最小货币单位整数。
  // 金额口径（冻结）：本夹具全部为分量级——5000 = 50.00 元、700 = 7.00 元。
  // ---------------------------------------------------------------------------------------------

  @Test
  void submitDailyClosing_singleCurrency_writesGroupedSummaryWithAmounts() {
    LocalDate date = LocalDate.of(2025, 1, 1);
    when(reports.listSucceededIntentsBetween(any(), any(), any(), any())).thenReturn(List.of(
        intent(11L, "CNY", "CASH", "5000"), intent(12L, "CNY", "ALIPAY", "3000")));
    when(shiftRepository.listClosedBetween(any(), any(), any(), any(), any())).thenReturn(List.of(shiftWithCurrency(21L, "CNY", "-100")));
    when(reports.summarizeRefunds(any(), any(), any(), any()))
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
    ArgumentCaptor<DailyClosing> captor = ArgumentCaptor.forClass(DailyClosing.class);
    verify(dailyClosingRepository).insert(captor.capture());
    assertEquals("CNY", captor.getValue().currencyCode());
    assertEquals("{\"businessDate\":\"2025-01-01\",\"storeId\":2,\"currencyCode\":\"CNY\",\"mixedCurrency\":false,"
        + "\"currencies\":[{\"currencyCode\":\"CNY\",\"collectionCount\":2,\"collectedAmount\":8000,\"cashCount\":1,"
        + "\"cashAmount\":5000,\"refundCount\":1,\"refundAmount\":2000,\"shiftCount\":1,\"shiftDifferenceAmount\":-100,"
        + "\"providers\":[{\"provider\":\"ALIPAY\",\"count\":1,\"amount\":3000},"
        + "{\"provider\":\"CASH\",\"count\":1,\"amount\":5000}]}]}", captor.getValue().summaryJson());
    assertEquals(summary, DailyClosingSummary.parse(captor.getValue().summaryJson()));
  }

  @Test
  void submitDailyClosing_multipleCurrencies_groupsByCurrencyAndMarksMixed() {
    LocalDate date = LocalDate.of(2025, 1, 2);
    when(reports.listSucceededIntentsBetween(any(), any(), any(), any())).thenReturn(List.of(
        intent(11L, "CNY", "CASH", "5000"), intent(12L, "USD", "CASH", "700"),
        intent(13L, "USD", "WECHAT", "300")));
    when(shiftRepository.listClosedBetween(any(), any(), any(), any(), any())).thenReturn(List.of(shiftWithCurrency(21L, "CNY", "100")));
    when(reports.summarizeRefunds(any(), any(), any(), any())).thenReturn(List.of());

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

    ArgumentCaptor<DailyClosing> captor = ArgumentCaptor.forClass(DailyClosing.class);
    verify(dailyClosingRepository).insert(captor.capture());
    // 混币种时列上保留当时租户币种（缺省 USD），混币种事实由 summary.mixedCurrency 表达
    assertEquals("USD", captor.getValue().currencyCode());
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

    ArgumentCaptor<DailyClosing> captor = ArgumentCaptor.forClass(DailyClosing.class);
    verify(dailyClosingRepository).insert(captor.capture());
    // 空营业日也必须落完整结构：不写 null、不抛错
    assertNotNull(captor.getValue().summaryJson());
    assertFalse(captor.getValue().summaryJson().contains("null"),
        captor.getValue().summaryJson());
  }

  @Test
  void submitDailyClosing_shiftDifferences_sumOnlySameCurrencyShifts() {
    // 同一天两个 CNY 班次 + 一个 USD 班次：长短款必须按班次币种快照分别汇总，
    // 把 100 + (-40) + (-50) 相加会得出一个没有业务含义的「长短款」。
    when(reports.listSucceededIntentsBetween(any(), any(), any(), any())).thenReturn(List.of());
    when(shiftRepository.listClosedBetween(any(), any(), any(), any(), any())).thenReturn(List.of(
        shiftWithCurrency(21L, "CNY", "100"), shiftWithCurrency(22L, "CNY", "-40"),
        shiftWithCurrency(23L, "USD", "-50")));
    when(reports.summarizeRefunds(any(), any(), any(), any())).thenReturn(List.of());

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
    when(reports.listSucceededIntentsBetween(any(), any(), any(), any())).thenReturn(List.of(intent(11L, "CNY", "CASH", "5000")));
    when(shiftRepository.listClosedBetween(any(), any(), any(), any(), any())).thenReturn(List.of());
    when(reports.summarizeRefunds(any(), any(), any(), any())).thenReturn(List.of());

    DailyClosingDto first = service.submitDailyClosing(1L, 2L, date, 7L);
    ArgumentCaptor<DailyClosing> firstInsert = ArgumentCaptor.forClass(DailyClosing.class);
    verify(dailyClosingRepository).insert(firstInsert.capture());

    // 第二次提交：沿用既有实现——仍按当时数据重算汇总后 insert，由唯一键
    // uk_pay_daily_tenant_store_date 拒绝（本方法不吞异常、不改成覆盖更新）。
    List<DailyClosing> rejected = new ArrayList<>();
    doAnswer(invocation -> {
      rejected.add(invocation.getArgument(0));
      throw new DuplicateKeyException("uk_pay_daily_tenant_store_date");
    }).when(dailyClosingRepository).insert(any(DailyClosing.class));

    assertThrows(DuplicateKeyException.class, () -> service.submitDailyClosing(1L, 2L, date, 7L));

    assertEquals(1, rejected.size());
    assertEquals(firstInsert.getValue().summaryJson(), rejected.get(0).summaryJson());
    assertEquals(first.summary(), DailyClosingSummary.parse(rejected.get(0).summaryJson()));
    assertEquals(5000L, first.summary().currencies().get(0).collectedAmount());
  }

  @Test
  void submitDailyClosing_fractionalMinorUnitAmount_rejectsInsteadOfRounding() {
    // 5000.5 分是「非法分」：与交班现金同一冻结口径，拒绝而不是静默取整成 5000 或 5001。
    when(reports.listSucceededIntentsBetween(any(), any(), any(), any())).thenReturn(List.of(intent(11L, "CNY", "CASH", "5000.5")));

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
    DailyClosing closing = new DailyClosing(9L, 1L, 2L, LocalDate.of(2025, 1, 7), 7L, null, "USD",
        "{\"businessDate\":", "SUBMITTED", null, null);

    DailyClosingDto dto = DailyClosingDto.from(closing);

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
    when(reports.listSucceededIntentsBetween(any(), any(), any(), any())).thenReturn(List.of(intent(11L, "CNY", "CASH", "5000")));
    when(reports.listConfirmedCollects(any(), any(), any(), any())).thenReturn(List.of(
        collect("CNY", "{\"remainingAmount\":0,\"collectedByMethod\":["
            + "{\"method\":\"WALLET\",\"amount\":3000},{\"method\":\"CASH\",\"amount\":5000}]}")));
    when(shiftRepository.listClosedBetween(any(), any(), any(), any(), any())).thenReturn(List.of());
    when(reports.summarizeRefunds(any(), any(), any(), any())).thenReturn(List.of());

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
    when(reports.listSucceededIntentsBetween(any(), any(), any(), any())).thenReturn(List.of(intent(11L, "CNY", "CASH", "5000")));
    when(reports.listConfirmedCollects(any(), any(), any(), any())).thenReturn(List.of(
        collect("CNY", "{\"remainingAmount\":0,\"collectedByMethod\":["
            + "{\"method\":\"POINT\",\"amount\":2000},{\"method\":\"CASH\",\"amount\":5000}]}")));
    when(shiftRepository.listClosedBetween(any(), any(), any(), any(), any())).thenReturn(List.of());
    when(reports.summarizeRefunds(any(), any(), any(), any())).thenReturn(List.of());

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
    when(reports.listSucceededIntentsBetween(any(), any(), any(), any())).thenReturn(List.of(intent(11L, "CNY", "CASH", "5000")));
    when(reports.listConfirmedCollects(any(), any(), any(), any())).thenReturn(List.of(
        collect("CNY", "{\"collectedByMethod\":[{\"method\":\"WALLET\",\"amount\":3000},"
            + "{\"method\":\"POINT\",\"amount\":2000}]}")));
    when(shiftRepository.listClosedBetween(any(), any(), any(), any(), any())).thenReturn(List.of());
    when(reports.summarizeRefunds(any(), any(), any(), any())).thenReturn(List.of());

    service.submitDailyClosing(1L, 2L, date, 7L);

    ArgumentCaptor<DailyClosing> captor = ArgumentCaptor.forClass(DailyClosing.class);
    verify(dailyClosingRepository).insert(captor.capture());
    String json = captor.getValue().summaryJson();
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
    when(reports.listSucceededIntentsBetween(any(), any(), any(), any())).thenReturn(List.of());
    when(reports.listConfirmedCollects(any(), any(), any(), any())).thenReturn(List.of(
        collect("CNY", "{\"collectedByMethod\":[{\"method\":\"WALLET\",\"amount\":3000}]}"),
        collect("USD", "{\"collectedByMethod\":[{\"method\":\"POINT\",\"amount\":700}]}")));
    when(shiftRepository.listClosedBetween(any(), any(), any(), any(), any())).thenReturn(List.of());
    when(reports.summarizeRefunds(any(), any(), any(), any())).thenReturn(List.of());

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
    when(reports.listSucceededIntentsBetween(any(), any(), any(), any())).thenReturn(List.of());
    when(reports.listConfirmedCollects(any(), any(), any(), any())).thenReturn(List.of(
        collect("CNY", null),
        collect(null, "   "),
        collect("CNY", "{\"businessDate\":"),
        collect("CNY", "{}"),
        collect("CNY", "{\"collectedByMethod\":\"WALLET\"}"),
        collect("CNY", "{\"collectedByMethod\":[{\"amount\":3000},{\"method\":\"WALLET\"},"
            + "{\"method\":\"WALLET\",\"amount\":\"x\"},{\"method\":\"WALLET\",\"amount\":0}]}"),
        collect("CNY", "{\"collectedByMethod\":[{\"method\":\"WALLET\",\"amount\":3000}]}")));
    when(shiftRepository.listClosedBetween(any(), any(), any(), any(), any())).thenReturn(List.of());
    when(reports.summarizeRefunds(any(), any(), any(), any())).thenReturn(List.of());

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
    when(shiftRepository.insert(any(Shift.class))).thenThrow(new IllegalStateException("db down"));

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
    when(shiftRepository.findById(1L)).thenReturn(java.util.Optional.ofNullable(shift("CLOSED")));

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
    Shift shift = withOpenedAt(withOpeningCash(shift("OPEN"), "100"), LocalDateTime.of(2025, 1, 1, 8, 0));
    when(shiftRepository.findById(shift.id())).thenReturn(java.util.Optional.of(shift));
    when(shiftRepository.updateClosing(any(Shift.class))).thenReturn(true);
    when(reports.sumCashSucceeded(any(), any(), any(), any(), any()))
        .thenReturn(new BigDecimal("500"));

    service.closeShift(shift.id(), new BigDecimal("600"), "现金全额收款无差异");

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
    when(dailyClosingRepository.insert(any(DailyClosing.class)))
        .thenThrow(new DuplicateKeyException("uk_pay_daily_tenant_store_date"));

    assertThrows(DuplicateKeyException.class, () -> service.submitDailyClosing(1L, 2L, date, 7L));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("cashier.daily_closing.close", record.action());
    assertEquals("营业日结", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("DuplicateKeyException", record.errorCode());
    assertNull(record.idempotencyKey());
  }

  private static ConfirmedCollectLine collect(String currencyCode, String responseJson) {
    return new ConfirmedCollectLine(currencyCode, responseJson);
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

  private static PaymentIntent intent(Long id, String currencyCode, String provider, String amount) {
    return new PaymentIntent(id, 1L, 2L, null, null, provider, null, new BigDecimal(amount),
        currencyCode, "SUCCEEDED", null, null, null, null);
  }

  private static Shift shiftWithCurrency(Long id, String currencyCode, String differenceAmount) {
    return new Shift(id, 1L, 2L, null, null, null, null, null, null, null,
        new BigDecimal(differenceAmount), currencyCode, "CLOSED", null, null);
  }

  private static RefundCurrencyAggregate refund(String currencyCode, long count, String amount) {
    return new RefundCurrencyAggregate(currencyCode, count, new BigDecimal(amount));
  }

  private static Shift shift(String status) {
    return new Shift(1L, 1L, 2L, null, null, null, null, null, null, null, null, null, status, null, null);
  }

  /** 不可变聚合按需生成新实例（record 没有 setter）。 */
  private static Shift withOpeningCash(Shift shift, String openingCash) {
    return new Shift(shift.id(), shift.tenantId(), shift.storeId(), shift.terminalId(),
        shift.operatorId(), shift.openedAt(), shift.closedAt(), new BigDecimal(openingCash),
        shift.expectedCash(), shift.actualCash(), shift.differenceAmount(), shift.currencyCode(),
        shift.status(), shift.createdAt(), shift.updatedAt());
  }

  /** 不可变聚合按需生成新实例（record 没有 setter）。 */
  private static Shift withOpenedAt(Shift shift, LocalDateTime openedAt) {
    return new Shift(shift.id(), shift.tenantId(), shift.storeId(), shift.terminalId(),
        shift.operatorId(), openedAt, shift.closedAt(), shift.openingCash(), shift.expectedCash(),
        shift.actualCash(), shift.differenceAmount(), shift.currencyCode(), shift.status(),
        shift.createdAt(), shift.updatedAt());
  }
}

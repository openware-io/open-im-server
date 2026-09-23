package io.openware.platform.admin.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.ReportTimeBuckets;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.platform.admin.infra.persistence.mapper.ReportMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 销售报表（{@code /admin/reports/sales}）的统计 + 明细口径单测。
 *
 * <p>覆盖：
 * <ul>
 *   <li>统计桶：销售额 / 订单数 / 客单价 / 折扣 / 退款 / 作废 / 收款 + 支付方式构成（含「其它」兜底）；</li>
 *   <li>明细分页：total 与同一过滤条件一致、页大小上限、页码越界收敛到 1、空页不发构成查询；</li>
 *   <li>统计与明细**同一份 from/to/granularity/status**（明细 total == 统计订单数之和）；</li>
 *   <li>日/周/月/年粒度边界（跨月周、跨年周、月初月末、年末年初）；</li>
 *   <li>混币种：分行 + 信封 currencyCode=null / mixedCurrency=true（禁止跨行求和）；</li>
 *   <li>{@code from > to} → 400；非法状态 → 400。</li>
 * </ul>
 */
class ReportSalesControllerTest {

    private static final long TENANT_ID = 1001L;
    private static final long STORE_ID = 2501L;

    private final ReportMapper reportMapper = mock(ReportMapper.class);
    private final ReportController controller = new ReportController(reportMapper);

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    /** 统计桶的完整口径：金额、客单价、退款、作废、收款构成（现金/线上/储值/积分/其它）。 */
    @Test
    @DisplayName("统计：销售额/订单数/客单价/折扣/退款/作废 + 支付方式构成")
    void bucketsAggregateSalesAndPaymentComposition() {
        setTenant();
        stubBuckets(List.of(
                sales("2026-09-19", "USD", 2, "30000", "2000", "30000", 1, "5000"),
                sales("2026-09-20", "USD", 1, "10000", "0", "0", 0, "0")), List.of(
                refund("2026-09-19", "USD", 1, "1500")), List.of(
                payment("2026-09-19", "USD", "CASH", 1, "20000"),
                payment("2026-09-19", "USD", "WALLET", 1, "5000"),
                payment("2026-09-19", "USD", "POINT", 1, "3000"),
                payment("2026-09-19", "USD", "ALIPAY", 1, "2000"),
                payment("2026-09-19", "USD", "NEW_CHANNEL", 1, "100")));
        stubDetails(3, List.of());

        ReportController.SalesReport report = controller.sales(STORE_ID, "2026-09-01", "2026-09-30", null, null,
                1, 20);

        assertEquals("DAY", report.granularity());
        assertEquals("Asia/Shanghai", report.businessZone(), "响应必须声明营业日时区，避免按 UTC 自然日理解");
        assertEquals("USD", report.currencyCode());
        assertFalse(report.mixedCurrency());
        assertEquals(2, report.buckets().size());

        ReportController.SalesReport.BucketRow first = report.buckets().get(0);
        assertEquals("2026-09-19", first.bucket().label());
        assertEquals(2, first.orderCount());
        assertEquals(0, first.salesAmount().compareTo(new BigDecimal("30000")), "销售额取有效单据 total_amount");
        assertEquals(0, first.discountAmount().compareTo(new BigDecimal("2000")));
        assertEquals(0, first.averageTicketAmount().compareTo(new BigDecimal("15000")), "客单价 = 销售额 / 订单数");
        assertEquals(1, first.refundCount());
        assertEquals(0, first.refundAmount().compareTo(new BigDecimal("1500")));
        assertEquals(1, first.voidedCount());
        assertEquals(0, first.voidedAmount().compareTo(new BigDecimal("5000")), "作废金额单独成列，不并进销售额");
        assertEquals(5, first.paymentCount());
        assertEquals(0, first.collectedAmount().compareTo(new BigDecimal("30100")));
        assertEquals(0, first.cashAmount().compareTo(new BigDecimal("20000")));
        assertEquals(0, first.onlineAmount().compareTo(new BigDecimal("2000")), "ALIPAY/WECHAT/STRIPE 归线上");
        assertEquals(0, first.walletAmount().compareTo(new BigDecimal("5000")), "WALLET 即 A380 储值币");
        assertEquals(0, first.pointsAmount().compareTo(new BigDecimal("3000")), "POINT 即积分");
        assertEquals(0, first.otherAmount().compareTo(new BigDecimal("100")),
                "未归类渠道必须显式暴露，否则构成之和与收款对不上");
        BigDecimal composition = first.cashAmount().add(first.onlineAmount()).add(first.walletAmount())
                .add(first.pointsAmount()).add(first.otherAmount());
        assertEquals(0, composition.compareTo(first.collectedAmount()), "构成之和必须等于收款金额");
    }

    /** 统计与明细同一口径：明细 total 等于统计订单数之和（DAY 粒度下逐桶相加）。 */
    @Test
    @DisplayName("明细 total 与统计订单数之和一致（同一 from/to/status）")
    void detailTotalMatchesBucketOrderCount() {
        setTenant();
        stubBuckets(List.of(
                sales("2026-09-19", "USD", 2, "30000", "0", "0", 0, "0"),
                sales("2026-09-20", "USD", 3, "40000", "0", "0", 0, "0")), List.of(), List.of());
        stubDetails(5, List.of(detail(11L, "2026-09-20T17:00:00", "2026-09-21", "USD", "COMPLETED")));

        ReportController.SalesReport report = controller.sales(STORE_ID, "2026-09-01", "2026-09-30", null, null,
                1, 20);

        long bucketOrderCount = report.buckets().stream().mapToLong(
                ReportController.SalesReport.BucketRow::orderCount).sum();
        assertEquals(5, report.details().total());
        assertEquals(bucketOrderCount, report.details().total(), "统计与明细必须能对上账");
        assertEquals(1, report.details().current());
        assertEquals(20, report.details().size());
    }

    /** 明细行字段 + 支付构成按订单归集（构成只查当前页的订单）。 */
    @Test
    @DisplayName("明细：单据字段 + 每单支付构成")
    void detailsCarryFieldsAndPaymentComposition() {
        setTenant();
        stubBuckets(List.of(sales("2026-09-19", "USD", 1, "25000", "0", "0", 0, "0")), List.of(), List.of());
        stubDetails(1, List.of(detail(77L, "2026-09-19T18:30:00", "2026-09-20", "USD", "WAITING_SETTLEMENT")));
        when(reportMapper.selectOrderPaymentComposition(any(), eq(List.of(77L)))).thenReturn(List.of(
                composition(77L, "CASH", "20000"),
                composition(77L, "POINT", "3000"),
                composition(77L, "WALLET", "2000")));

        ReportController.SalesReport.DetailRow row = controller.sales(
                STORE_ID, "2026-09-01", "2026-09-30", null, null, 1, 20).details().records().get(0);

        assertEquals(77L, row.orderId());
        assertEquals("A380-20260919-0001", row.orderNo());
        assertEquals("2026-09-19T18:30:00", row.createdAt(), "createdAt 是存储值（与订单页时间口径一致）");
        assertEquals("2026-09-20", row.businessDate().toString(),
                "跨零点通宵场次：存储 09-19 18:30（门店 09-20 02:30）归属营业日 09-20");
        assertEquals("K12", row.roomName());
        assertEquals("WAITING_SETTLEMENT", row.status());
        assertEquals(0, row.totalAmount().compareTo(new BigDecimal("25000")));
        assertEquals(0, row.payment().cashAmount().compareTo(new BigDecimal("20000")));
        assertEquals(0, row.payment().pointsAmount().compareTo(new BigDecimal("3000")));
        assertEquals(0, row.payment().walletAmount().compareTo(new BigDecimal("2000")));
        assertEquals(0, row.payment().onlineAmount().signum());
    }

    /** 分页契约：pageSize 上限 100、page 越界收敛到 1、offset = (page-1)*size。 */
    @Test
    @DisplayName("明细分页：pageSize 封顶 100，page<1 收敛到 1")
    void pagingIsClamped() {
        setTenant();
        stubBuckets(List.of(), List.of(), List.of());
        stubDetails(500, List.of());

        controller.sales(STORE_ID, "2026-09-01", "2026-09-30", null, null, 3, 500);
        verify(reportMapper).selectSalesDetails(eq(TENANT_ID), eq(STORE_ID), any(), any(), isNull(),
                eq(100), eq(200L), eq(ReportTimeBuckets.BUSINESS_DAY_SHIFT_SECONDS));
        verify(reportMapper).countSalesDetails(eq(TENANT_ID), eq(STORE_ID), any(), any(), isNull());

        controller.sales(STORE_ID, "2026-09-01", "2026-09-30", null, null, 0, 0);
        verify(reportMapper).selectSalesDetails(eq(TENANT_ID), eq(STORE_ID), any(), any(), isNull(),
                eq(20), eq(0L), eq(ReportTimeBuckets.BUSINESS_DAY_SHIFT_SECONDS));
    }

    /** 空结果不发构成查询（分页为 0 行时不得再打一次库）。 */
    @Test
    @DisplayName("空明细：不再查询支付构成")
    void emptyDetailsSkipCompositionQuery() {
        setTenant();
        stubBuckets(List.of(), List.of(), List.of());
        stubDetails(0, List.of());

        ReportController.SalesReport report = controller.sales(STORE_ID, "2026-09-01", "2026-09-30", null, null,
                1, 20);

        assertTrue(report.details().records().isEmpty());
        assertEquals(0, report.details().total());
        verify(reportMapper, never()).selectOrderPaymentComposition(any(), any());
        verify(reportMapper, never()).selectSalesDetails(any(), any(), any(), any(), any(), anyInt(), anyLong(),
                anyInt());
    }

    /** 明细分页用 status 显式过滤时，统计仍按默认口径（响应回显实际生效的 status）。 */
    @Test
    @DisplayName("status 显式过滤：明细按该状态取，响应回显 status")
    void explicitStatusFiltersDetails() {
        setTenant();
        stubBuckets(List.of(), List.of(), List.of());
        when(reportMapper.countSalesDetails(any(), any(), any(), any(), eq("VOIDED"))).thenReturn(1L);
        when(reportMapper.selectSalesDetails(any(), any(), any(), any(), eq("VOIDED"), anyInt(), anyLong(), anyInt()))
                .thenReturn(List.of(detail(9L, "2026-09-19T10:00:00", "2026-09-19", "USD", "VOIDED")));

        ReportController.SalesReport report = controller.sales(STORE_ID, "2026-09-01", "2026-09-30", null,
                "voided", 1, 20);

        assertEquals("VOIDED", report.status(), "status 大小写归一后回显");
        assertEquals(1, report.details().total());
        assertEquals("VOIDED", report.details().records().get(0).status());
    }

    /** 非法状态：400，不静默返回空表。 */
    @Test
    @DisplayName("非法 status → 400 ORDER_STATUS_INVALID")
    void invalidStatusIsRejected() {
        setTenant();

        ApiException error = assertThrows(ApiException.class,
                () -> controller.sales(STORE_ID, "2026-09-01", "2026-09-30", null, "NOT_A_STATUS", 1, 20));

        assertEquals(400, error.getStatus());
        assertEquals("ORDER_STATUS_INVALID", error.getCode());
    }

    /** 混币种：分行 + 信封声明（禁止跨行求和）。 */
    @Test
    @DisplayName("混币种：currencyCode=null、mixedCurrency=true")
    void mixedCurrencyIsFlagged() {
        setTenant();
        stubBuckets(List.of(
                sales("2026-09-19", "USD", 1, "10000", "0", "0", 0, "0"),
                sales("2026-09-19", "CNY", 1, "5000", "0", "0", 0, "0")), List.of(), List.of());
        stubDetails(0, List.of());

        ReportController.SalesReport report = controller.sales(STORE_ID, "2026-09-01", "2026-09-30", null, null,
                1, 20);

        assertNull(report.currencyCode(), "混币种时信封不给单币种，避免被当成一个合计");
        assertTrue(report.mixedCurrency());
        assertEquals(2, report.buckets().size(), "同桶不同币种必须分行");
        assertEquals("CNY", report.buckets().get(0).currencyCode());
        assertEquals("USD", report.buckets().get(1).currencyCode());
    }

    /** 粒度边界：跨月周、跨年周、月初月末、年末年初。 */
    @Test
    @DisplayName("WEEK：跨月周 / 跨年周都并成一个桶（周一起、按 ISO 周年命名）")
    void weekGranularityHandlesMonthAndYearBoundaries() {
        setTenant();
        stubBuckets(List.of(
                sales("2026-08-31", "USD", 1, "100", "0", "0", 0, "0"),
                sales("2026-09-06", "USD", 1, "200", "0", "0", 0, "0"),
                sales("2025-12-29", "USD", 1, "300", "0", "0", 0, "0"),
                sales("2026-01-04", "USD", 1, "400", "0", "0", 0, "0")), List.of(), List.of());
        stubDetails(0, List.of());

        ReportController.SalesReport report = controller.sales(STORE_ID, "2025-12-01", "2026-09-30", "WEEK", null,
                1, 20);

        assertEquals(2, report.buckets().size(), "跨月周与跨年周各并成一行");
        ReportController.SalesReport.BucketRow crossYear = report.buckets().get(0);
        assertEquals("2026年第1周", crossYear.bucket().label(), "2025-12-29 属 2026 年第 1 周");
        assertEquals("2025-12-29", crossYear.bucket().start().toString());
        assertEquals("2026-01-04", crossYear.bucket().end().toString());
        assertEquals(2, crossYear.orderCount());
        ReportController.SalesReport.BucketRow crossMonth = report.buckets().get(1);
        assertEquals("2026年第36周", crossMonth.bucket().label());
        assertEquals("2026-08-31", crossMonth.bucket().start().toString());
        assertEquals(2, crossMonth.orderCount());
    }

    /** 粒度边界：月初月末 / 年末年初。 */
    @Test
    @DisplayName("MONTH/YEAR：月初月末与年末年初归桶正确")
    void monthAndYearGranularity() {
        setTenant();
        stubBuckets(List.of(
                sales("2026-01-01", "USD", 1, "100", "0", "0", 0, "0"),
                sales("2026-12-31", "USD", 1, "200", "0", "0", 0, "0"),
                sales("2027-01-01", "USD", 1, "300", "0", "0", 0, "0")), List.of(), List.of());
        stubDetails(0, List.of());

        ReportController.SalesReport byYear = controller.sales(STORE_ID, "2026-01-01", "2027-01-01", "YEAR", null,
                1, 20);
        assertEquals(2, byYear.buckets().size());
        assertEquals("2026年", byYear.buckets().get(0).bucket().label());
        assertEquals("2026-01-01", byYear.buckets().get(0).bucket().start().toString());
        assertEquals("2026-12-31", byYear.buckets().get(0).bucket().end().toString());
        assertEquals(2, byYear.buckets().get(0).orderCount(), "年末最后一天的场次仍在 2026 年内");
        assertEquals("2027年", byYear.buckets().get(1).bucket().label());

        ReportController.SalesReport byMonth = controller.sales(STORE_ID, "2026-01-01", "2027-01-01", "MONTH", null,
                1, 20);
        assertEquals(3, byMonth.buckets().size(), "2026-01 / 2026-12 / 2027-01");
        assertEquals("2026-12", byMonth.buckets().get(1).bucket().label());
        assertEquals("2026-12-01", byMonth.buckets().get(1).bucket().start().toString());
        assertEquals("2026-12-31", byMonth.buckets().get(1).bucket().end().toString());
    }

    /** 取数窗口按营业日对齐：from/to 经 TimeRangeParams 解析后换算成半开区间交给 Mapper。 */
    @Test
    @DisplayName("取数窗口 = 营业日 [from, to] 对应的存储值半开区间")
    void windowIsAlignedToBusinessDays() {
        setTenant();
        stubBuckets(List.of(), List.of(), List.of());
        stubDetails(0, List.of());

        controller.sales(STORE_ID, "2026-09-01", "2026-09-30", null, null, 1, 20);

        LocalDateTime expectedFrom = ReportTimeBuckets.windowFrom(java.time.LocalDate.of(2026, 9, 1));
        LocalDateTime expectedTo = ReportTimeBuckets.windowToExclusive(java.time.LocalDate.of(2026, 9, 30));
        verify(reportMapper).countSalesDetails(eq(TENANT_ID), eq(STORE_ID), eq(expectedFrom), eq(expectedTo),
                isNull());
    }

    /** from > to → 400（复用统一时间解析，不再静默交换两端）。 */
    @Test
    @DisplayName("from > to → 400 TIME_RANGE_INVALID")
    void invertedRangeIsRejected() {
        setTenant();

        ApiException error = assertThrows(ApiException.class,
                () -> controller.sales(STORE_ID, "2026-09-30", "2026-09-01", null, null, 1, 20));

        assertEquals(400, error.getStatus());
        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, error.getCode());
    }

    /** 没有租户上下文必须 401。 */
    @Test
    @DisplayName("缺少租户上下文 → 401")
    void missingTenantContextIsRejected() {
        ApiException error = assertThrows(ApiException.class,
                () -> controller.sales(null, "2026-09-01", "2026-09-30", null, null, 1, 20));

        assertEquals(401, error.getStatus());
    }

    // —— 造数与断言辅助 ——

    private static void setTenant() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 0L, 0));
    }

    private void stubBuckets(List<Map<String, Object>> orders, List<Map<String, Object>> refunds,
                             List<Map<String, Object>> payments) {
        when(reportMapper.selectSalesOrders(any(), any(), any(), any(), anyInt())).thenReturn(orders);
        when(reportMapper.selectRefunds(any(), any(), any(), any(), anyInt())).thenReturn(refunds);
        when(reportMapper.selectSalesPayments(any(), any(), any(), any(), anyInt())).thenReturn(payments);
    }

    private void stubDetails(long total, List<Map<String, Object>> records) {
        when(reportMapper.countSalesDetails(any(), any(), any(), any(), any())).thenReturn(total);
        if (total > 0) {
            when(reportMapper.selectSalesDetails(any(), any(), any(), any(), any(), anyInt(), anyLong(), anyInt()))
                    .thenReturn(records);
        }
    }

    private static Map<String, Object> sales(String businessDate, String currencyCode, long orderCount,
                                             String salesAmount, String discountAmount, String paidAmount,
                                             long voidedCount, String voidedAmount) {
        Map<String, Object> row = base(businessDate, currencyCode);
        row.put("order_count", orderCount);
        row.put("sales_amount", new BigDecimal(salesAmount));
        row.put("discount_amount", new BigDecimal(discountAmount));
        row.put("paid_amount", new BigDecimal(paidAmount));
        row.put("voided_count", voidedCount);
        row.put("voided_amount", new BigDecimal(voidedAmount));
        return row;
    }

    private static Map<String, Object> payment(String businessDate, String currencyCode, String provider,
                                               long count, String amount) {
        Map<String, Object> row = base(businessDate, currencyCode);
        row.put("provider", provider);
        row.put("payment_count", count);
        row.put("payment_amount", new BigDecimal(amount));
        return row;
    }

    private static Map<String, Object> refund(String businessDate, String currencyCode, long count, String amount) {
        Map<String, Object> row = base(businessDate, currencyCode);
        row.put("refund_count", count);
        row.put("refund_amount", new BigDecimal(amount));
        return row;
    }

    private static Map<String, Object> detail(Long orderId, String createdAt, String businessDate,
                                              String currencyCode, String status) {
        Map<String, Object> row = base(businessDate, currencyCode);
        row.put("order_id", orderId);
        row.put("order_no", "A380-20260919-0001");
        row.put("created_at", createdAt);
        row.put("room_name", "K12");
        row.put("status", status);
        row.put("subtotal_amount", new BigDecimal("25000"));
        row.put("discount_amount", new BigDecimal("0"));
        row.put("total_amount", new BigDecimal("25000"));
        row.put("paid_amount", new BigDecimal("25000"));
        row.put("refundable_amount", new BigDecimal("25000"));
        return row;
    }

    private static Map<String, Object> composition(Long orderId, String provider, String amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("order_id", orderId);
        row.put("provider", provider);
        row.put("currency_code", "USD");
        row.put("payment_count", 1L);
        row.put("payment_amount", new BigDecimal(amount));
        return row;
    }

    private static Map<String, Object> base(String businessDate, String currencyCode) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("store_id", STORE_ID);
        row.put("store_name", "A店");
        row.put("business_date", businessDate);
        row.put("currency_code", currencyCode);
        return row;
    }
}

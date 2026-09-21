package com.gvchat.platform.admin.api.controller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.infrastructure.time.TimeRangeParams;
import com.gvchat.platform.admin.infra.persistence.mapper.ReportMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 支付报表（{@code /admin/reports/payments}）的**回归测试**：这是线上真实 500 的复现。
 *
 * <h2>缺陷（已在 6635995a 之后的 dc3fde4d 引入，本次修）</h2>
 * 聚合行由 {@code byKey.computeIfAbsent(key, k -> new PaymentsAgg())} 创建，而
 * {@code PaymentsAgg.currencyCode} 只在**退款分支**的工厂方法 {@code payments(m)} 里赋值：
 * <ol>
 *   <li>收款行（绝大多数行）的 {@code currencyCode} 恒为 {@code null} —— 行内币种快照丢失，
 *       前端只能回落到「当前币种」渲染历史金额（违反 16_CURRENCY_CONVENTIONS §5「快照优先」）；</li>
 *   <li>排序末位 {@code .thenComparing(PaymentsReport.Row::currencyCode)}（无 nullsLast）在
 *       两行同 (门店, 统计桶) 时对 null 调 {@code compareTo} → {@code NullPointerException}
 *       → 全局兜底 500 {@code INTERNAL_ERROR}。实测栈：
 *       <pre>
 *       java.lang.NullPointerException: Cannot invoke "java.lang.Comparable.compareTo(Object)"
 *         because the return value of "java.util.function.Function.apply(Object)" is null
 *         at com.gvchat.platform.admin.api.controller.ReportController.payments(ReportController.java:151)
 *       </pre>
 *       触发条件正是「同一门店同一营业日存在两种币种快照」—— 也就是这段代码本来要支持的
 *       混币种场景（历史 USD 流水 + 门店/租户切到 CNY 后的新流水）。</li>
 * </ol>
 *
 * <p>本测试把两种症状都钉住：币种必须按行落位、同桶多币种不得抛异常、null 币种排序必须安全。
 */
class ReportPaymentsReportContractTest {

    private static final long TENANT_ID = 1001L;
    private static final long STORE_ID = 2501L;

    private final ReportMapper reportMapper = mock(ReportMapper.class);
    private final ReportController controller = new ReportController(reportMapper);

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    /** 回归：同一门店同一营业日的两种币种必须各自成行，绝不能 500。 */
    @Test
    @DisplayName("同 (门店, 桶) 多币种：分行返回而不是 NPE 500")
    void sameStoreSameBucketDifferentCurrenciesDoNotThrow() {
        setTenant();
        when(reportMapper.selectCollections(any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                collection("2026-09-19", "USD", 3, "30000"),
                collection("2026-09-19", "CNY", 1, "8800")));
        when(reportMapper.selectRefunds(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        ReportController.PaymentsReport report = assertDoesNotThrow(() -> controller.payments(
                STORE_ID, "2026-09-01", "2026-09-30", null),
                "混币种（按币种分行）是这段代码要支持的主场景，不得因为排序抛 NPE");

        assertEquals(2, report.rows().size());
        assertEquals("CNY", report.rows().get(0).currencyCode(), "按币种升序（CNY < USD）");
        assertEquals("USD", report.rows().get(1).currencyCode());
        assertEquals(1, report.rows().get(0).collectionCount());
        assertEquals(3, report.rows().get(1).collectionCount());
    }

    /** 回归：收款行的币种快照必须来自该行记录，不能是 null。 */
    @Test
    @DisplayName("收款行带上自身的币种快照（不再恒为 null）")
    void collectionRowsKeepTheirOwnCurrencySnapshot() {
        setTenant();
        when(reportMapper.selectCollections(any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                collection("2026-09-19", "USD", 2, "20000")));
        when(reportMapper.selectRefunds(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        ReportController.PaymentsReport.Row row = controller.payments(
                STORE_ID, "2026-09-01", "2026-09-30", null).rows().get(0);

        assertEquals("USD", row.currencyCode(), "行内币种快照丢失会让前端拿当前币种去渲染历史金额");
        assertEquals(STORE_ID, row.storeId());
        assertEquals("A店", row.storeName());
        assertEquals(0, row.collectedAmount().compareTo(new BigDecimal("20000")));
    }

    /** 退款并入同一 (门店, 桶, 币种) 行：退款不会把收款行的币种冲成 null。 */
    @Test
    @DisplayName("退款并入同键行，币种保持收款行的快照")
    void refundMergesIntoCollectionRowKeepingCurrency() {
        setTenant();
        when(reportMapper.selectCollections(any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                collection("2026-09-19", "CNY", 2, "20000")));
        when(reportMapper.selectRefunds(any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                refund("2026-09-19", "CNY", 1, "3000")));

        ReportController.PaymentsReport report = controller.payments(
                STORE_ID, "2026-09-01", "2026-09-30", null);

        assertEquals(1, report.rows().size(), "同 (门店, 桶, 币种) 的收款与退款并成一行");
        ReportController.PaymentsReport.Row row = report.rows().get(0);
        assertEquals("CNY", row.currencyCode());
        assertEquals(2, row.collectionCount());
        assertEquals(1, row.refundCount());
        assertEquals(0, row.refundAmount().compareTo(new BigDecimal("3000")));
    }

    /** null 币种（历史脏行）排序必须安全：两个门店同一天、币种都缺失也不得抛异常。 */
    @Test
    @DisplayName("币种快照为 null 的行排序不抛异常（nullsLast）")
    void nullCurrencySortingIsNullSafe() {
        setTenant();
        when(reportMapper.selectCollections(any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                collection(2501L, "2026-09-19", null, 1, "100"),
                collection(2502L, "2026-09-19", null, 1, "200")));
        when(reportMapper.selectRefunds(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        ReportController.PaymentsReport report = assertDoesNotThrow(() -> controller.payments(
                null, "2026-09-01", "2026-09-30", null));

        assertEquals(2, report.rows().size());
        assertEquals("USD", report.rows().get(0).currencyCode(), "空币种按全仓缺省 USD 归集");
    }

    /** 粒度：同一 ISO 周的多个营业日并成一个桶（周起点周一）。 */
    @Test
    @DisplayName("granularity=WEEK：同周两天并成一行，桶标签按 ISO 周")
    void weeklyGranularityMergesSameWeekDays() {
        setTenant();
        when(reportMapper.selectCollections(any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                collection("2026-09-14", "USD", 1, "100"),
                collection("2026-09-20", "USD", 2, "200"),
                collection("2026-09-21", "USD", 4, "400")));
        when(reportMapper.selectRefunds(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        ReportController.PaymentsReport report = controller.payments(
                STORE_ID, "2026-09-01", "2026-09-30", "WEEK");

        assertEquals("WEEK", report.granularity());
        assertEquals(2, report.rows().size(), "09-14~09-20 是一周，09-21 起是下一周");
        assertEquals("2026年第38周", report.rows().get(0).bucket().label());
        assertEquals(3, report.rows().get(0).collectionCount(), "同周两天合并计数");
        assertEquals("2026年第39周", report.rows().get(1).bucket().label());
    }

    /** 粒度：月/年桶也要能吃下多个营业日（月起止按自然月）。 */
    @Test
    @DisplayName("granularity=MONTH：跨月数据分成两个自然月桶")
    void monthlyGranularitySplitsByCalendarMonth() {
        setTenant();
        when(reportMapper.selectCollections(any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                collection("2026-08-31", "USD", 1, "100"),
                collection("2026-09-01", "USD", 1, "100")));
        when(reportMapper.selectRefunds(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        ReportController.PaymentsReport report = controller.payments(
                STORE_ID, "2026-08-01", "2026-09-30", "MONTH");

        assertEquals(2, report.rows().size());
        assertEquals("2026-08", report.rows().get(0).bucket().label());
        assertEquals("2026-08-01", report.rows().get(0).bucket().start().toString());
        assertEquals("2026-08-31", report.rows().get(0).bucket().end().toString());
        assertEquals("2026-09", report.rows().get(1).bucket().label());
    }

    /** 时间区间：from > to 必须 400 TIME_RANGE_INVALID（复用 TimeRangeParams，不再静默交换两端）。 */
    @Test
    @DisplayName("from > to → 400 TIME_RANGE_INVALID")
    void invertedRangeIsRejected() {
        setTenant();

        ApiException error = assertThrows(ApiException.class,
                () -> controller.payments(STORE_ID, "2026-09-30", "2026-09-01", null));

        assertEquals(400, error.getStatus());
        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, error.getCode());
    }

    /** 非法时间格式同样 400（不猜、不忽略）。 */
    @Test
    @DisplayName("非法时间格式 → 400 TIME_RANGE_INVALID")
    void malformedRangeIsRejected() {
        setTenant();

        ApiException error = assertThrows(ApiException.class,
                () -> controller.payments(STORE_ID, "2026/09/01", null, null));

        assertEquals(400, error.getStatus());
        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, error.getCode());
    }

    /** 非法粒度不能静默降级成 DAY（否则用户以为按季/按周看了，其实看的是按天）。 */
    @Test
    @DisplayName("非法 granularity → 400 GRANULARITY_INVALID")
    void invalidGranularityIsRejected() {
        setTenant();

        ApiException error = assertThrows(ApiException.class,
                () -> controller.payments(STORE_ID, "2026-09-01", "2026-09-30", "QUARTER"));

        assertEquals(400, error.getStatus());
        assertEquals("GRANULARITY_INVALID", error.getCode());
    }

    /** 未传区间时给最近 30 天（含今天）的默认窗口，且响应回显实际生效的营业日区间。 */
    @Test
    @DisplayName("不传 from/to：默认最近 30 天，响应回显生效区间")
    void defaultRangeIsThirtyDays() {
        setTenant();
        when(reportMapper.selectCollections(any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        when(reportMapper.selectRefunds(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        ReportController.PaymentsReport report = controller.payments(null, null, null, null);

        assertTrue(report.rows().isEmpty());
        assertTrue(report.from().plusDays(30).equals(report.to()) || report.from().isBefore(report.to()));
        assertNull(report.rows().stream().findFirst().orElse(null));
    }

    // —— 造数辅助 ——

    private static void setTenant() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 0L, 0));
    }

    private static Map<String, Object> collection(String businessDate, String currencyCode, long count,
                                                  String amount) {
        return collection(STORE_ID, businessDate, currencyCode, count, amount);
    }

    /** LinkedHashMap 允许 null 值，用于「币种快照为空」的历史行场景。 */
    private static Map<String, Object> collection(Long storeId, String businessDate, String currencyCode,
                                                  long count, String amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("store_id", storeId);
        row.put("store_name", "A店");
        row.put("business_date", businessDate);
        row.put("currency_code", currencyCode);
        row.put("collection_count", count);
        row.put("collected_amount", new BigDecimal(amount));
        return row;
    }

    private static Map<String, Object> refund(String businessDate, String currencyCode, long count, String amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("store_id", STORE_ID);
        row.put("store_name", "A店");
        row.put("business_date", businessDate);
        row.put("currency_code", currencyCode);
        row.put("refund_count", count);
        row.put("refund_amount", new BigDecimal(amount));
        return row;
    }
}

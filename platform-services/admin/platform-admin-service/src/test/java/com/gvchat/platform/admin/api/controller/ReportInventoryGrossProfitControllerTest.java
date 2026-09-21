package com.gvchat.platform.admin.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.gvchat.platform.admin.infra.persistence.mapper.ReportMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 库存成本与毛利报表（{@code /admin/reports/inventory-gross-profit}）的聚合口径单测。
 *
 * <p>只验证应用层聚合：成本 = 净售出数量 × 移动加权平均成本、毛利 / 毛利率、收入为 0 的边界、
 * 以及**跨币种不静默求和**（同一 (门店, 统计桶, 币种) 才合并，混币种时信封 currencyCode=null、
 * mixedCurrency=true）。SQL 本身由 ReportMapper 承载，此处按 mapper 契约造数据。
 */
class ReportInventoryGrossProfitControllerTest {

    private static final long TENANT_ID = 1001L;
    private static final long STORE_ID = 2501L;

    private final ReportMapper reportMapper = mock(ReportMapper.class);
    private final ReportController controller = new ReportController(reportMapper);

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    /** 单币种：收入 10000，成本 2×400 = 800 → 毛利 9200、毛利率 0.92。 */
    @Test
    void singleCurrencyComputesCostGrossProfitAndMargin() {
        setTenant();
        when(reportMapper.selectItemRevenue(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(revenue("2026-01-15", "CNY", "10000")));
        when(reportMapper.selectInventoryConsumptionCost(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(cost("2026-01-15", "CNY", "400", "2")));

        ReportController.InventoryGrossProfitReport report = controller.inventoryGrossProfit(
                null, "2026-01-01", "2026-01-31", null);

        assertEquals("CNY", report.currencyCode());
        assertFalse(report.mixedCurrency());
        assertEquals("PERIOD_END_MOVING_AVERAGE", report.costBasis());
        assertEquals("DAY", report.granularity(), "不传粒度即按天，老调用方行为不变");
        assertEquals(1, report.rows().size());
        ReportController.InventoryGrossProfitReport.Row row = report.rows().get(0);
        assertEquals(0, row.revenueAmount().compareTo(new BigDecimal("10000")), "期间收入取订单明细金额");
        assertEquals(0, row.costAmount().compareTo(new BigDecimal("800")), "成本 = 数量 × 移动加权平均成本");
        assertEquals(0, row.grossProfitAmount().compareTo(new BigDecimal("9200")), "毛利 = 收入 − 成本");
        assertEquals(0, row.grossMarginRate().compareTo(new BigDecimal("0.9200")), "毛利率 = 毛利 / 收入");
        assertEquals(0, row.soldQuantity().compareTo(new BigDecimal("2")));
        assertEquals(0, row.uncostedQuantity().signum(), "有成本的数量不算未覆盖");
    }

    /** 多币种：CNY 与 USD 各自成行、各自算毛利，绝不跨币种相减或求和。 */
    @Test
    void mixedCurrencyKeepsRowsSeparateAndFlagsEnvelope() {
        setTenant();
        when(reportMapper.selectItemRevenue(any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                revenue("2026-01-15", "CNY", "10000"),
                revenue("2026-01-15", "USD", "5000")));
        when(reportMapper.selectInventoryConsumptionCost(any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                cost("2026-01-15", "CNY", "400", "2"),
                cost("2026-01-15", "USD", "1000", "1")));

        ReportController.InventoryGrossProfitReport report = controller.inventoryGrossProfit(
                null, "2026-01-01", "2026-01-31", null);

        assertNull(report.currencyCode(), "混币种时信封不给单币种，避免被当成一个合计");
        assertTrue(report.mixedCurrency());
        assertEquals(2, report.rows().size(), "同一门店同一天的不同币种必须分行");
        ReportController.InventoryGrossProfitReport.Row cny = rowOf(report, "CNY");
        assertEquals(0, cny.revenueAmount().compareTo(new BigDecimal("10000")));
        assertEquals(0, cny.grossProfitAmount().compareTo(new BigDecimal("9200")));
        ReportController.InventoryGrossProfitReport.Row usd = rowOf(report, "USD");
        assertEquals(0, usd.revenueAmount().compareTo(new BigDecimal("5000")));
        assertEquals(0, usd.grossProfitAmount().compareTo(new BigDecimal("4000")));
        assertTrue(report.rows().stream()
                        .noneMatch(row -> row.revenueAmount().compareTo(new BigDecimal("15000")) == 0),
                "绝不允许把 CNY 与 USD 的金额相加成一个数字");
    }

    /** 收入为 0：毛利率返回 null（不做 0 除，也不假装是 0%），毛利为负成本。 */
    @Test
    void zeroRevenueReturnsNullMarginRate() {
        setTenant();
        when(reportMapper.selectItemRevenue(any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        when(reportMapper.selectInventoryConsumptionCost(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(cost("2026-01-15", "CNY", "400", "2")));

        ReportController.InventoryGrossProfitReport.Row row = controller.inventoryGrossProfit(
                STORE_ID, "2026-01-01", "2026-01-31", null).rows().get(0);

        assertEquals(0, row.revenueAmount().signum());
        assertEquals(0, row.costAmount().compareTo(new BigDecimal("800")));
        assertEquals(0, row.grossProfitAmount().compareTo(new BigDecimal("-800")));
        assertNull(row.grossMarginRate(), "收入为 0 时毛利率必须是 null");
    }

    /** 平均成本缺失（0）：成本不计、数量计入 uncostedQuantity，让「无成本」不被误读成「零成本」。 */
    @Test
    void missingAverageCostIsReportedAsUncostedQuantity() {
        setTenant();
        when(reportMapper.selectItemRevenue(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(revenue("2026-01-15", "USD", "900")));
        when(reportMapper.selectInventoryConsumptionCost(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(cost("2026-01-15", "USD", "0", "3")));

        ReportController.InventoryGrossProfitReport.Row row = controller.inventoryGrossProfit(
                null, "2026-01-01", "2026-01-31", null).rows().get(0);

        assertEquals(0, row.costAmount().signum());
        assertEquals(0, row.grossProfitAmount().compareTo(new BigDecimal("900")));
        assertEquals(0, row.uncostedQuantity().compareTo(new BigDecimal("3")));
    }

    /** 回补（REVERSE）数量为负：净售出数量与成本一起冲回，不会把「销售未发生」算成成本。 */
    @Test
    void revertedQuantityNetsAgainstSoldQuantity() {
        setTenant();
        when(reportMapper.selectItemRevenue(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(revenue("2026-01-15", "CNY", "3000")));
        when(reportMapper.selectInventoryConsumptionCost(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(cost("2026-01-15", "CNY", "500", "4"),
                        cost("2026-01-15", "CNY", "500", "-1")));

        ReportController.InventoryGrossProfitReport.Row row = controller.inventoryGrossProfit(
                null, "2026-01-01", "2026-01-31", null).rows().get(0);

        assertEquals(0, row.soldQuantity().compareTo(new BigDecimal("3")), "4 件消耗 − 1 件回补 = 净 3 件");
        assertEquals(0, row.costAmount().compareTo(new BigDecimal("1500")));
    }

    /** 币种快照为空的历史行按全仓缺省 USD 归集，不因为空值而拆出一行「无币种」。 */
    @Test
    void nullCurrencySnapshotFallsBackToUsd() {
        setTenant();
        Map<String, Object> blankCurrency = revenue("2026-01-15", null, "100");
        when(reportMapper.selectItemRevenue(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(blankCurrency));
        when(reportMapper.selectInventoryConsumptionCost(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        ReportController.InventoryGrossProfitReport report = controller.inventoryGrossProfit(
                null, "2026-01-01", "2026-01-31", null);

        assertEquals("USD", report.currencyCode());
        assertEquals("USD", report.rows().get(0).currencyCode());
    }

    /** 按周看：同一 ISO 周的多个营业日必须并成一个桶（桶起点=周一，标签走词表之外的稳定文本）。 */
    @Test
    void weeklyGranularityMergesDaysOfSameIsoWeek() {
        setTenant();
        when(reportMapper.selectItemRevenue(any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                revenue("2026-09-14", "CNY", "100"),
                revenue("2026-09-15", "CNY", "200"),
                revenue("2026-09-20", "CNY", "300"),
                revenue("2026-09-21", "CNY", "400")));
        when(reportMapper.selectInventoryConsumptionCost(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        ReportController.InventoryGrossProfitReport report = controller.inventoryGrossProfit(
                null, "2026-09-01", "2026-09-30", "WEEK");

        assertEquals("WEEK", report.granularity());
        assertEquals(2, report.rows().size(), "09-14~09-20 一周、09-21 起下一周");
        assertEquals("2026年第38周", report.rows().get(0).bucket().label());
        assertEquals("2026-09-14", report.rows().get(0).bucket().start().toString());
        assertEquals(0, report.rows().get(0).revenueAmount().compareTo(new BigDecimal("600")),
                "同周三天必须并成一行");
        assertEquals("2026年第39周", report.rows().get(1).bucket().label());
    }

    /** 没有租户上下文必须 401，而不是返回 200 + 空报表（否则调用方会把「无上下文」当成功）。 */
    @Test
    void missingTenantContextIsRejected() {
        ApiException error = assertThrows(ApiException.class,
                () -> controller.inventoryGrossProfit(null, null, null, null));

        assertEquals(401, error.getStatus());
    }

    // —— 造数与断言辅助 ——

    private static void setTenant() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 0L, 0));
    }

    private static ReportController.InventoryGrossProfitReport.Row rowOf(
            ReportController.InventoryGrossProfitReport report, String currency) {
        return report.rows().stream().filter(row -> currency.equals(row.currencyCode())).findFirst().orElseThrow();
    }

    private static Map<String, Object> revenue(String businessDate, String currencyCode, String amount) {
        Map<String, Object> row = baseRow(businessDate, currencyCode);
        row.put("revenue_amount", new BigDecimal(amount));
        return row;
    }

    private static Map<String, Object> cost(String businessDate, String currencyCode, String avgCost,
                                            String netQuantity) {
        Map<String, Object> row = baseRow(businessDate, currencyCode);
        row.put("material_id", 5L);
        row.put("avg_cost", new BigDecimal(avgCost));
        row.put("net_quantity", new BigDecimal(netQuantity));
        return row;
    }

    /** LinkedHashMap 允许 null 值，用于「币种快照为空」的历史行场景。 */
    private static Map<String, Object> baseRow(String businessDate, String currencyCode) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("store_id", STORE_ID);
        row.put("store_name", "A店");
        row.put("business_date", businessDate);
        row.put("currency_code", currencyCode);
        return row;
    }
}

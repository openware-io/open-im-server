package com.gvchat.platform.admin.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.admin.infra.persistence.mapper.ReportMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 商品/服务销售排行（{@code /admin/reports/sales-items}）的口径单测。
 *
 * <p>覆盖：品类参数归一（大小写/非法值）、占比在**同币种内**计算（混币种不越界相加）、
 * topN 默认与上限、混币种信封、空结果。
 */
class ReportSalesItemsControllerTest {

    private ReportMapper reportMapper;
    private ReportController controller;

    @BeforeEach
    void setUp() {
        reportMapper = mock(ReportMapper.class);
        controller = new ReportController(reportMapper);
        TenantContextHolder.set(new TenantContext(100L, null, null, 0L, 0));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private static Map<String, Object> row(String category, String name, String currency,
                                           String quantity, String salesAmount, String discount,
                                           long storeCount, long orderCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item_category", category);
        m.put("item_name", name);
        m.put("currency_code", currency);
        m.put("quantity", new BigDecimal(quantity));
        m.put("sales_amount", new BigDecimal(salesAmount));
        m.put("discount_amount", new BigDecimal(discount));
        m.put("store_count", storeCount);
        m.put("order_count", orderCount);
        return m;
    }

    @Test
    @DisplayName("排行：按销售额降序返回明细名/品类/销量/金额/折扣/订单数/门店数")
    void mapsRankingRows() {
        when(reportMapper.selectSalesItems(anyLong(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(
                        row("PRODUCT", "青岛啤酒", "CNY", "12", "360.00", "40.00", 2, 5),
                        row("ROOM_FEE", "包厢费（含 1 名服务人员）", "CNY", "3", "240.00", "0.00", 1, 3)));

        ReportController.SalesItemsReport report = controller.salesItems(null, "2026-09-01", "2026-09-20",
                null, null);

        assertEquals(2, report.rows().size());
        ReportController.SalesItemsReport.Row beer = report.rows().get(0);
        assertEquals("PRODUCT", beer.itemType());
        assertEquals("青岛啤酒", beer.itemName());
        assertEquals("CNY", beer.currencyCode());
        assertEquals(0, new BigDecimal("360.00").compareTo(beer.salesAmount()));
        assertEquals(0, new BigDecimal("12").compareTo(beer.quantity()));
        assertEquals(2, beer.storeCount());
        assertEquals(5, beer.orderCount());
        // 占比在同币种内算：360 / (360 + 240) = 0.6
        assertEquals(0, new BigDecimal("0.6000").compareTo(beer.share()));
        assertEquals("CNY", report.totals().get(0).currencyCode());
        assertEquals(0, new BigDecimal("600.00").compareTo(report.totals().get(0).salesAmount()));
        assertTrue(!report.mixedCurrency());
    }

    @Test
    @DisplayName("混币种：占比按各自币种的合计算，不跨币种相加；信封 mixedCurrency=true")
    void shareIsPerCurrency() {
        when(reportMapper.selectSalesItems(anyLong(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(
                        row("PRODUCT", "洋酒", "USD", "1", "100.00", "0.00", 1, 1),
                        row("SERVICE", "点歌服务", "USD", "3", "300.00", "0.00", 1, 2),
                        row("PRODUCT", "啤酒", "CNY", "6", "180.00", "0.00", 1, 3)));

        ReportController.SalesItemsReport report = controller.salesItems(null, null, null, null, null);

        assertEquals(0, new BigDecimal("0.2500").compareTo(report.rows().get(0).share()));  // 100 / 400 (USD)
        assertEquals(0, new BigDecimal("1.0000").compareTo(report.rows().get(2).share()));  // 180 / 180 (CNY)
        assertTrue(report.mixedCurrency(), "多币种要标注 mixedCurrency，前端禁止跨行求和");
        assertNull(report.currencyCode(), "混币种时信封 currencyCode 为空");
        assertEquals(2, report.totals().size(), "逐币种给合计");
    }

    @Test
    @DisplayName("品类参数：小写归一为大写；未登记的值按「全部」处理（不返回空表）")
    void normalisesItemType() {
        when(reportMapper.selectSalesItems(anyLong(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        controller.salesItems(null, null, null, "product", null);
        verify(reportMapper).selectSalesItems(anyLong(), any(), any(), any(), eq("PRODUCT"), anyInt());

        controller.salesItems(null, null, null, "SERVICE", null);
        verify(reportMapper).selectSalesItems(anyLong(), any(), any(), any(), eq("SERVICE"), anyInt());

        controller.salesItems(null, null, null, "NOT_A_CATEGORY", null);
        verify(reportMapper).selectSalesItems(anyLong(), any(), any(), any(), isNull(), anyInt());
    }

    @Test
    @DisplayName("topN：默认 20，上限 200，下限 1")
    void clampsTopN() {
        when(reportMapper.selectSalesItems(anyLong(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        controller.salesItems(null, null, null, null, null);
        controller.salesItems(null, null, null, null, 500);
        controller.salesItems(null, null, null, null, 0);

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(reportMapper, org.mockito.Mockito.times(3))
                .selectSalesItems(anyLong(), any(), any(), any(), any(), limit.capture());
        assertEquals(List.of(20, 200, 1), limit.getAllValues());
    }

    @Test
    @DisplayName("空结果：rows/totals 都为空数组，不报错")
    void emptyResult() {
        when(reportMapper.selectSalesItems(anyLong(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new ArrayList<>());

        ReportController.SalesItemsReport report = controller.salesItems(null, "2026-09-01", "2026-09-02", null, null);

        assertTrue(report.rows().isEmpty());
        assertTrue(report.totals().isEmpty());
        assertNull(report.currencyCode());
    }
}

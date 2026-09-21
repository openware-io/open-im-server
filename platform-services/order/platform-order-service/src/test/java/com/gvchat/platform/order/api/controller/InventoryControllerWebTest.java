package com.gvchat.platform.order.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.infrastructure.time.TimeRangeParams;
import com.gvchat.infrastructure.time.TimeRangeParams.TimeRange;
import com.gvchat.platform.order.application.InventoryApplicationService;
import com.gvchat.platform.order.handler.GlobalExceptionHandler;
import com.gvchat.platform.order.infra.persistence.po.InventoryMaterialPo;
import com.gvchat.platform.order.infra.persistence.po.InventoryTransactionPo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * InventoryController Web 层契约（standalone MockMvc + mock 服务）：
 * 物料新建/编辑请求体与列表响应都带上 purchasePrice，口径是**最小货币单位（分）**。
 *
 * <p>覆盖三条语义：null = 不修改（原样透传）、0 = 清空（原样透传给服务归一为 NULL）、
 * 非法值由服务抛 {@code PURCHASE_PRICE_INVALID} 时回 400 且 message 是可读中文。
 *
 * <p>列表端点（materials/transactions/costs）一律返回 MyBatis-Plus 分页信封
 * （{@code records}/{@code total}/{@code current}/{@code size}），查询参数逐个透传给服务；
 * costs 的币种信封按全部命中行计算（由服务保证，这里守响应形态）。
 */
class InventoryControllerWebTest {

    private final InventoryApplicationService service = mock(InventoryApplicationService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new InventoryController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void setUp() {
        TenantContextHolder.set(new TenantContext(1L, 1L, 3L, 4L, 1,
                List.of("inventory.material.manage", "inventory.material.view", "inventory.transaction.view")));
        InventoryMaterialPo saved = new InventoryMaterialPo();
        saved.setId(9L);
        saved.setPurchasePrice(new BigDecimal("350"));
        when(service.createMaterial(any())).thenReturn(saved);
        when(service.updateMaterial(any(), any())).thenReturn(saved);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void createMaterialPassesPurchasePriceInMinorUnits() throws Exception {
        mvc.perform(post("/admin/inventory/materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":3,\"materialCode\":\"M-1\",\"name\":\"可乐\",\"purchasePrice\":350}"))
                .andExpect(status().isOk());

        assertEquals(0, capturedCreate().purchasePrice().compareTo(new BigDecimal("350")));
    }

    /** 留空不提交该字段：服务侧收到 null，即「不修改/未填」。 */
    @Test
    void createMaterialWithoutPurchasePricePassesNull() throws Exception {
        mvc.perform(post("/admin/inventory/materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":3,\"materialCode\":\"M-1\",\"name\":\"可乐\"}"))
                .andExpect(status().isOk());

        assertNull(capturedCreate().purchasePrice());
    }

    /** 0 = 清空：原样透传给服务（由服务归一为 NULL），不在 Web 层提前丢掉。 */
    @Test
    void updateMaterialPassesZeroPurchasePriceAsClear() throws Exception {
        mvc.perform(put("/admin/inventory/materials/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":3,\"materialCode\":\"M-1\",\"name\":\"可乐\",\"purchasePrice\":0}"))
                .andExpect(status().isOk());

        ArgumentCaptor<InventoryApplicationService.MaterialCommand> captor =
                ArgumentCaptor.forClass(InventoryApplicationService.MaterialCommand.class);
        verify(service).updateMaterial(any(), captor.capture());
        assertEquals(0, captor.getValue().purchasePrice().signum());
    }

    @Test
    void createMaterialReturns400WithReadableCodeWhenPurchasePriceInvalid() throws Exception {
        doThrow(new BusinessException("PURCHASE_PRICE_INVALID", "采购价（最小货币单位）不能为负"))
                .when(service).createMaterial(any());

        mvc.perform(post("/admin/inventory/materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":3,\"materialCode\":\"M-1\",\"name\":\"可乐\",\"purchasePrice\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_PRICE_INVALID"))
                .andExpect(jsonPath("$.message").value("采购价（最小货币单位）不能为负"));
    }

    /** 物料列表：返回分页信封（records/total/current/size），不再是裸数组。 */
    @Test
    void materialsListReturnsPageEnvelopeWithPurchasePrice() throws Exception {
        InventoryMaterialPo material = new InventoryMaterialPo();
        material.setId(9L);
        material.setPurchasePrice(new BigDecimal("350"));
        Page<InventoryMaterialPo> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(material));
        when(service.listMaterials(1L, 20L, 3L, null, null, null, TimeRange.none())).thenReturn(page);

        mvc.perform(get("/admin/inventory/materials").param("storeId", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].purchasePrice").value(350))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.current").value(1))
                .andExpect(jsonPath("$.size").value(20));
    }

    /** 物料列表：page/pageSize/status/category/keyword 逐个透传（不在 Web 层做筛选或截断）。 */
    @Test
    void materialsListPassesPagingAndFiltersThrough() throws Exception {
        when(service.listMaterials(2L, 50L, null, "INACTIVE", "耗材", "纸巾", TimeRange.none()))
                .thenReturn(new Page<>(2, 50));

        mvc.perform(get("/admin/inventory/materials")
                        .param("page", "2").param("pageSize", "50")
                        .param("status", "INACTIVE").param("category", "耗材").param("keyword", "纸巾"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isArray())
                .andExpect(jsonPath("$.total").value(0));

        verify(service).listMaterials(2L, 50L, null, "INACTIVE", "耗材", "纸巾", TimeRange.none());
    }

    /** 流水列表：类型/来源/日期区间/关键字全部透传，响应是分页信封。 */
    @Test
    void transactionsListPassesFiltersThroughAndReturnsPage() throws Exception {
        InventoryTransactionPo transaction = new InventoryTransactionPo();
        transaction.setId(77L);
        transaction.setTransactionType("CONSUME");
        Page<InventoryTransactionPo> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(transaction));
        when(service.listTransactions(1L, 20L, 7L, "CONSUME", "ORDER_ITEM", TimeRangeParams.parse("2026-09-01", "2026-09-30"), "可乐")).thenReturn(page);

        mvc.perform(get("/admin/inventory/transactions")
                        .param("materialId", "7").param("transactionType", "CONSUME").param("sourceType", "ORDER_ITEM")
                        .param("from", "2026-09-01").param("to", "2026-09-30").param("keyword", "可乐"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].transactionType").value("CONSUME"))
                .andExpect(jsonPath("$.total").value(1));
    }

    /**
     * 日期区间非法（{@code from > to}）：**控制器**在解析阶段就抛 400 {@code TIME_RANGE_INVALID}
     * （全仓统一错误码，不再是原来的 {@code INVENTORY_FILTER_INVALID}），且不会打到服务层。
     */
    @Test
    void transactionsRejectInvertedDateRangeWith400BeforeTouchingService() throws Exception {
        mvc.perform(get("/admin/inventory/transactions")
                        .param("from", "2026-09-30").param("to", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"))
                .andExpect(jsonPath("$.message").value("时间区间不合法，起始时间不能晚于结束时间"));

        verifyNoInteractions(service);
    }

    /** 时间格式非法：同样是 400 {@code TIME_RANGE_INVALID}，不静默忽略（忽略会让用户以为筛过了）。 */
    @Test
    void transactionsRejectMalformedDateWith400() throws Exception {
        mvc.perform(get("/admin/inventory/transactions").param("from", "2026/09/01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"));

        verifyNoInteractions(service);
    }

    /** 物料列表同样支持时间区间，并且物料/流水两个端点用的是同一个错误码与解析器。 */
    @Test
    void materialsListAcceptsTimeRangeAndRejectsInvertedRange() throws Exception {
        Page<InventoryMaterialPo> page = new Page<>(1, 20, 0);
        when(service.listMaterials(1L, 20L, 3L, null, null, null,
                TimeRangeParams.parse("2026-09-01", "2026-09-30"))).thenReturn(page);

        mvc.perform(get("/admin/inventory/materials")
                        .param("storeId", "3").param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mvc.perform(get("/admin/inventory/materials")
                        .param("from", "2026-09-30").param("to", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"));
    }

    /** 库存成本：信封里既有分页字段也有币种字段，total 是命中物料条数（不是当前页条数）。 */
    @Test
    void costsReturnsPageEnvelopeWithCurrencyOfAllMatchedRows() throws Exception {
        InventoryApplicationService.InventoryCostRow row = new InventoryApplicationService.InventoryCostRow(
                3L, 7L, "DRINK-001", "可乐", "瓶",
                new BigDecimal("2.000000"), new BigDecimal("300.000000"), "CNY", new BigDecimal("600.000000"));
        InventoryApplicationService.InventoryCostReport report =
                new InventoryApplicationService.InventoryCostReport(1, 20, 42);
        report.setRecords(List.of(row));
        report.setDataAsOf(Instant.parse("2026-09-14T10:15:30Z"));
        report.setStoreId(3L);
        report.setCurrencyCode("CNY");
        report.setMixedCurrency(false);
        when(service.inventoryCosts(1L, 20L, 3L, "可乐")).thenReturn(report);

        mvc.perform(get("/admin/inventory/costs")
                        .param("storeId", "3").param("keyword", "可乐").param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].materialCode").value("DRINK-001"))
                .andExpect(jsonPath("$.records[0].inventoryCost").value(600.000000))
                .andExpect(jsonPath("$.total").value(42))
                .andExpect(jsonPath("$.current").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.currencyCode").value("CNY"))
                .andExpect(jsonPath("$.mixedCurrency").value(false));
    }

    private InventoryApplicationService.MaterialCommand capturedCreate() {
        ArgumentCaptor<InventoryApplicationService.MaterialCommand> captor =
                ArgumentCaptor.forClass(InventoryApplicationService.MaterialCommand.class);
        verify(service).createMaterial(captor.capture());
        return captor.getValue();
    }
}

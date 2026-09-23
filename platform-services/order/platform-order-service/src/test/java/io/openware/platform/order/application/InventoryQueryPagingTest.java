package io.openware.platform.order.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.platform.order.infra.persistence.mapper.CatalogItemMapper;
import io.openware.platform.order.infra.persistence.mapper.InventoryMaterialMapper;
import io.openware.platform.order.infra.persistence.mapper.InventoryStockMapper;
import io.openware.platform.order.infra.persistence.mapper.InventoryTransactionMapper;
import io.openware.platform.order.infra.persistence.mapper.ProductMapper;
import io.openware.platform.order.infra.persistence.po.InventoryMaterialPo;
import io.openware.platform.order.infra.persistence.po.InventoryStockPo;
import io.openware.platform.order.infra.persistence.po.InventoryTransactionPo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 仓库管理 / 出入库记录 / 库存成本三块的**分页与查询**口径（Mockito 单测，不连库）。
 *
 * <p>守住的契约：
 * <ul>
 *   <li>分页参数归一：{@code page < 1} → 1，{@code pageSize} 非正或 &gt; 200 → 默认 20（下发给 mapper 的 Page 就是这个值）；</li>
 *   <li>物料：状态/分类/关键字（名称或编码）条件与 {@code id desc} 排序；空白筛选不得变成「匹配空串」；</li>
 *   <li>流水：类型/来源精确匹配，{@code from}/{@code to} 按 {@code created_at} 闭区间（结束日 23:59:59.999999999），
 *       {@code from > to} 或日期格式非法 → 400 {@code INVENTORY_FILTER_INVALID}；</li>
 *   <li>流水关键字：先取命中物料 id 再 IN，命中为空直接空页（绝不下发 {@code IN ()}）；</li>
 *   <li>成本：分页作用于物料行，但 {@code total} 与币种信封（{@code currencyCode}/{@code mixedCurrency}）
 *       按全部命中行计算，翻页不漂移。</li>
 * </ul>
 *
 * <p>真库分页（LIMIT/OFFSET 与 count）由 {@code InventoryPagingIntegrationTest} 在 H2 上验证。
 */
class InventoryQueryPagingTest {

    private static final long TENANT_ID = 1L;
    private static final long STORE_ID = 3L;

    private final InventoryMaterialMapper materialMapper = mock(InventoryMaterialMapper.class);
    private final InventoryStockMapper stockMapper = mock(InventoryStockMapper.class);
    private final InventoryTransactionMapper transactionMapper = mock(InventoryTransactionMapper.class);
    private final ProductMapper productMapper = mock(ProductMapper.class);
    private final CatalogItemMapper catalogItemMapper = mock(CatalogItemMapper.class);
    private final InventoryApplicationService service = new InventoryApplicationService(
            materialMapper, stockMapper, transactionMapper, productMapper, catalogItemMapper);

    /** 下发给 mapper 的 Page（断言归一后的 current/size）。 */
    private Page<?> requestedPage;
    /** 下发给 mapper 的查询条件。 */
    private LambdaQueryWrapper<InventoryMaterialPo> materialQuery;
    private LambdaQueryWrapper<InventoryTransactionPo> transactionQuery;

    @BeforeAll
    static void initTableInfo() {
        for (Class<?> entity : List.of(InventoryMaterialPo.class, InventoryStockPo.class, InventoryTransactionPo.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
    }

    @BeforeEach
    void setUp() {
        requestedPage = null;
        materialQuery = null;
        transactionQuery = null;
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 0L, 0));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    // —— 分页参数归一 ——

    @Test
    void materialPageSizeFallsBackToDefaultWhenOutOfRange() {
        stubMaterialPage();

        service.listMaterials(1L, 500L, null, null, null, null, TimeRange.none());

        assertEquals(InventoryApplicationService.DEFAULT_PAGE_SIZE, requestedPage.getSize(),
                "pageSize 超过上限 200 必须回落到默认 20（而不是静默截断成 200）");
        assertEquals(1L, requestedPage.getCurrent());
    }

    @Test
    void materialPageArgsFallBackWhenNonPositive() {
        stubMaterialPage();

        service.listMaterials(0L, 0L, null, null, null, null, TimeRange.none());

        assertEquals(1L, requestedPage.getCurrent(), "page < 1 回落到第 1 页");
        assertEquals(InventoryApplicationService.DEFAULT_PAGE_SIZE, requestedPage.getSize(), "pageSize <= 0 回落到默认值");
    }

    @Test
    void materialPageSizeAtUpperBoundIsAccepted() {
        stubMaterialPage();

        service.listMaterials(1L, InventoryApplicationService.MAX_PAGE_SIZE, null, null, null, null, TimeRange.none());

        assertEquals(InventoryApplicationService.MAX_PAGE_SIZE, requestedPage.getSize());
    }

    // —— 物料筛选 ——

    @Test
    void materialsApplyStatusCategoryKeywordAndDescendingIdSort() {
        stubMaterialPage();

        service.listMaterials(1L, 20L, null, "ACTIVE", "耗材", "纸巾", TimeRange.none());

        String sql = materialQuery.getSqlSegment();
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("category"), sql);
        assertTrue(sql.contains("name LIKE"), "关键字要能命中物料名称: " + sql);
        assertTrue(sql.contains("material_code LIKE"), "关键字要能命中物料编码: " + sql);
        assertTrue(sql.contains("ORDER BY id DESC"), "排序必须是 id desc（与仓库其它分页端点一致）: " + sql);
    }

    @Test
    void blankFiltersAreIgnoredInsteadOfMatchingEmptyStrings() {
        stubMaterialPage();

        service.listMaterials(1L, 20L, null, "   ", null, "  ", TimeRange.none());

        String sql = materialQuery.getSqlSegment();
        assertFalse(sql.contains("status"), "空白状态必须当作未传，不能去匹配空串: " + sql);
        assertFalse(sql.contains("category"), sql);
        assertFalse(sql.contains("LIKE"), "空白关键字必须当作未传: " + sql);
    }

    // —— 流水筛选 ——

    @Test
    void transactionsApplyTypeSourceAndClosedDateRange() {
        stubTransactionPage();

        service.listTransactions(1L, 20L, 7L, "RECEIPT", "RECEIPT", TimeRangeParams.parse("2026-09-01", "2026-09-30"), null);

        String sql = transactionQuery.getSqlSegment();
        assertTrue(sql.contains("transaction_type"), sql);
        assertTrue(sql.contains("source_type"), sql);
        assertTrue(sql.contains("created_at >="), "时间区间过滤必须落在 created_at（本表没有 occurred_at 列）: " + sql);
        assertTrue(sql.contains("created_at <="), sql);
        assertTrue(sql.contains("ORDER BY id DESC"), sql);
        // 闭区间：起始日从 00:00:00 起，结束日收口到当天最后一毫秒（整天都能命中）
        assertTrue(transactionQuery.getParamNameValuePairs().values()
                        .contains(LocalDateTime.of(2026, 9, 1, 0, 0)),
                "起始端点必须按当天 00:00:00 收口");
        assertTrue(transactionQuery.getParamNameValuePairs().values()
                        .contains(LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000)),
                "结束端点必须按当天最后一毫秒收口（闭区间）；不用 LocalTime.MAX，避免被 DATETIME(3) 进位到次日");
    }

    /**
     * 时间参数的**解析与校验**已统一到 {@code TimeRangeParams}（全仓唯一实现），因此它不再属于本类：
     * <ul>
     *   <li>{@code from > to} / 格式非法 → {@code TimeRangeParamsTest} 断言 400 {@code TIME_RANGE_INVALID}；</li>
     *   <li>HTTP 层的 400 与「不落到服务层」→ {@code InventoryControllerWebTest} 断言。</li>
     * </ul>
     * 本类只保留「服务层拿到区间后 SQL 怎么拼」的断言（见上）。
     */

    /** 关键字没命中任何物料：直接空页，不下发 {@code IN ()}（那是非法 SQL），也不查流水。 */
    @Test
    void transactionsReturnEmptyPageWhenKeywordMatchesNoMaterial() {
        when(materialMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        Page<InventoryTransactionPo> result =
                service.listTransactions(1L, 20L, null, null, null, TimeRangeParams.parse(null, null), "不存在");

        assertEquals(0L, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
        verifyNoInteractions(transactionMapper);
    }

    /** 关键字命中物料：用 IN(id 集合) 过滤，不逐行回查（无 N+1）。 */
    @Test
    void transactionsFilterByMatchedMaterialIdsWithoutPerRowLookup() {
        InventoryMaterialPo hit = new InventoryMaterialPo();
        hit.setId(11L);
        when(materialMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(hit));
        stubTransactionPage();

        service.listTransactions(1L, 20L, null, null, null, TimeRangeParams.parse(null, null), "可乐");

        assertTrue(transactionQuery.getSqlSegment().contains("material_id IN"), transactionQuery.getSqlSegment());
        verify(materialMapper).selectList(any(LambdaQueryWrapper.class));
    }

    // —— 库存成本分页与信封口径 ——

    @Test
    void costsPageRowsWhileTotalAndCurrencyEnvelopeCoverAllMatchedRows() {
        stubCostSources(
                stock(11L, "2", "300", "CNY"),
                stock(12L, "1", "100", "CNY"),
                stock(13L, "1", "5", "USD"));

        InventoryApplicationService.InventoryCostReport first = service.inventoryCosts(1L, 2L, null, null);
        assertEquals(3L, first.getTotal(), "total 必须是命中物料条数，不是当前页条数");
        assertEquals(2, first.getRecords().size());
        assertEquals(1L, first.getCurrent());
        assertEquals(2L, first.getSize());
        assertTrue(first.isMixedCurrency(), "混币种判定必须按全部命中行");
        assertNull(first.getCurrencyCode(), "混币种时 envelope.currencyCode 必须为 null，禁止相加");
        assertEquals(STORE_ID, first.getStoreId());

        InventoryApplicationService.InventoryCostReport second = service.inventoryCosts(2L, 2L, null, null);
        assertEquals(3L, second.getTotal());
        assertEquals(1, second.getRecords().size());
        assertTrue(second.isMixedCurrency(), "翻到第 2 页时混币种信封不得漂移成 false");
        assertNull(second.getCurrencyCode());
    }

    @Test
    void costsEnvelopeGivesSingleCurrencyWhenAllRowsShareIt() {
        stubCostSources(stock(11L, "2", "300", "CNY"), stock(12L, "1", "100", "CNY"));

        InventoryApplicationService.InventoryCostReport report = service.inventoryCosts(1L, 20L, null, null);

        assertEquals(2L, report.getTotal());
        assertEquals("CNY", report.getCurrencyCode());
        assertFalse(report.isMixedCurrency());
    }

    /** 成本关键字：只保留命中物料的库存行，total 也随之收敛。 */
    @Test
    void costsFilterByMaterialKeyword() {
        InventoryMaterialPo hit = new InventoryMaterialPo();
        hit.setId(12L);
        when(materialMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(hit));
        stubCostSources(stock(11L, "2", "300", "CNY"), stock(12L, "1", "100", "CNY"));

        InventoryApplicationService.InventoryCostReport report = service.inventoryCosts(1L, 20L, null, "可乐");

        assertEquals(1L, report.getTotal());
        assertEquals(12L, report.getRecords().get(0).materialId());
    }

    /** 越界页返回空 records 而不是报错（与 MyBatis-Plus 超出总页数的行为一致）。 */
    @Test
    void costsOutOfRangePageReturnsEmptyRecords() {
        stubCostSources(stock(11L, "2", "300", "CNY"));

        InventoryApplicationService.InventoryCostReport report = service.inventoryCosts(99L, 20L, null, null);

        assertEquals(1L, report.getTotal());
        assertTrue(report.getRecords().isEmpty());
    }

    @Test
    void costsRejectForeignStore() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.inventoryCosts(1L, 20L, 999L, null));

        assertEquals("STORE_SCOPE_DENIED", error.getCode());
    }

    // —— 辅助 ——

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubMaterialPage(InventoryMaterialPo... records) {
        when(materialMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page page = invocation.getArgument(0);
            requestedPage = page;
            materialQuery = invocation.getArgument(1);
            page.setRecords(List.of(records));
            page.setTotal(records.length);
            return page;
        });
        when(materialMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubTransactionPage(InventoryTransactionPo... records) {
        when(transactionMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page page = invocation.getArgument(0);
            requestedPage = page;
            transactionQuery = invocation.getArgument(1);
            page.setRecords(List.of(records));
            page.setTotal(records.length);
            return page;
        });
    }

    private void stubCostSources(InventoryStockPo... stocks) {
        when(stockMapper.selectByStore(TENANT_ID, STORE_ID)).thenReturn(List.of(stocks));
        List<InventoryMaterialPo> materials = java.util.Arrays.stream(stocks)
                .map(stock -> material(stock.getMaterialId())).toList();
        when(materialMapper.selectBatchIds(any())).thenReturn(materials);
    }

    private static InventoryStockPo stock(Long materialId, String onHand, String avgCost, String currency) {
        InventoryStockPo stock = new InventoryStockPo();
        stock.setTenantId(TENANT_ID);
        stock.setStoreId(STORE_ID);
        stock.setMaterialId(materialId);
        stock.setOnHandQty(new BigDecimal(onHand));
        stock.setReservedQty(BigDecimal.ZERO);
        stock.setAvgCost(new BigDecimal(avgCost));
        stock.setCurrencyCode(currency);
        return stock;
    }

    private static InventoryMaterialPo material(Long id) {
        InventoryMaterialPo material = new InventoryMaterialPo();
        material.setId(id);
        material.setMaterialCode("M-" + id);
        material.setName("物料" + id);
        material.setUnit("件");
        return material;
    }
}

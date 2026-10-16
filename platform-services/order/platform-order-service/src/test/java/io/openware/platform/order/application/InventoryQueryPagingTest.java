package io.openware.platform.order.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.order.api.dto.InventoryMaterialView;
import io.openware.platform.order.api.dto.InventoryTransactionView;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.domain.inventory.model.InventoryMaterial;
import io.openware.platform.order.domain.inventory.model.InventoryStock;
import io.openware.platform.order.domain.inventory.model.InventoryTransaction;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository.MaterialPage;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository.MaterialQuery;
import io.openware.platform.order.domain.inventory.repository.InventoryStockRepository;
import io.openware.platform.order.domain.inventory.repository.InventoryTransactionRepository;
import io.openware.platform.order.domain.inventory.repository.InventoryTransactionRepository.TransactionPage;
import io.openware.platform.order.domain.inventory.repository.InventoryTransactionRepository.TransactionQuery;
import org.junit.jupiter.api.AfterEach;
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
 * <p>守住的契约（迁移后按**端口调用**断言，不再是下发给 mapper 的 SQL）：
 * <ul>
 *   <li>分页参数归一：{@code page < 1} → 1，{@code pageSize} 非正或 &gt; 200 → 默认 20
 *       （端口只接受「偏移 + 上限」，因此这里断言 {@code offset}/{@code limit} 与响应信封）；</li>
 *   <li>物料：状态/分类/关键字（名称或编码）条件变成 {@code MaterialQuery} 的字段；
 *       空白筛选不得变成「匹配空串」（必须是 null）；</li>
 *   <li>流水：类型/来源精确匹配，{@code from}/{@code to} 按 {@code created_at} 闭区间
 *       （结束日 23:59:59.999999999），端点值以 {@code LocalDateTime} 交给端口；</li>
 *   <li>流水关键字：先取命中物料 id 再按白名单过滤，命中为空直接空页（绝不下发 {@code IN ()}）；</li>
 *   <li>成本：分页作用于物料行，但 {@code total} 与币种信封（{@code currencyCode}/{@code mixedCurrency}）
 *       按全部命中行计算，翻页不漂移。</li>
 * </ul>
 *
 * <p><b>SQL 形状去适配器测试</b>：{@code LIKE} 的拼法、{@code ORDER BY id DESC}、{@code LIMIT/OFFSET}、
 * {@code IN} 白名单这些**查询语法**由适配器负责，断言在
 * {@code InventoryMaterialRepositoryAdapterTest} / {@code InventoryTransactionRepositoryAdapterTest}
 * （真实适配器 + mock mapper，用 {@code getTargetSql()} 求值）。本类只钉住「服务要什么」。
 */
class InventoryQueryPagingTest {

    private static final long TENANT_ID = 1L;
    private static final long STORE_ID = 3L;

    private final InventoryMaterialRepository materialRepository = mock(InventoryMaterialRepository.class);
    private final InventoryStockRepository stockRepository = mock(InventoryStockRepository.class);
    private final InventoryTransactionRepository transactionRepository = mock(InventoryTransactionRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final CatalogItemRepository catalogItemRepository = mock(CatalogItemRepository.class);
    private final InventoryApplicationService service = new InventoryApplicationService(
            materialRepository, stockRepository, transactionRepository, productRepository, catalogItemRepository);

    /** 下发给物料端口的查询条件（断言归一后的偏移/上限与过滤字段）。 */
    private MaterialQuery materialQuery;
    /** 下发给流水端口的查询条件。 */
    private TransactionQuery transactionQuery;

    @BeforeEach
    void setUp() {
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

        Page<InventoryMaterialView> result = service.listMaterials(1L, 500L, null, null, null, null, TimeRange.none());

        assertEquals(InventoryApplicationService.DEFAULT_PAGE_SIZE, materialQuery.limit(),
                "pageSize 超过上限 200 必须回落到默认 20（而不是静默截断成 200）");
        assertEquals(0L, materialQuery.offset(), "第 1 页的偏移必须是 0");
        assertEquals(InventoryApplicationService.DEFAULT_PAGE_SIZE, result.getSize());
        assertEquals(1L, result.getCurrent());
    }

    @Test
    void materialPageArgsFallBackWhenNonPositive() {
        stubMaterialPage();

        service.listMaterials(0L, 0L, null, null, null, null, TimeRange.none());

        assertEquals(0L, materialQuery.offset(), "page < 1 回落到第 1 页（偏移 0）");
        assertEquals(InventoryApplicationService.DEFAULT_PAGE_SIZE, materialQuery.limit(), "pageSize <= 0 回落到默认值");
    }

    @Test
    void materialPageSizeAtUpperBoundIsAccepted() {
        stubMaterialPage();

        service.listMaterials(1L, InventoryApplicationService.MAX_PAGE_SIZE, null, null, null, null, TimeRange.none());

        assertEquals(InventoryApplicationService.MAX_PAGE_SIZE, materialQuery.limit());
    }

    /** 第 N 页 → 偏移 (N-1)×limit：端口只认偏移，换算在应用层。 */
    @Test
    void materialPageNumberIsTranslatedToOffset() {
        stubMaterialPage();

        service.listMaterials(3L, 20L, null, null, null, null, TimeRange.none());

        assertEquals(40L, materialQuery.offset());
        assertEquals(20L, materialQuery.limit());
    }

    // —— 物料筛选 ——

    @Test
    void materialsApplyStatusCategoryKeywordAndDescendingIdSort() {
        stubMaterialPage();

        service.listMaterials(1L, 20L, null, "ACTIVE", "耗材", "纸巾", TimeRange.none());

        assertEquals(TENANT_ID, materialQuery.tenantId());
        assertEquals("ACTIVE", materialQuery.status());
        assertEquals("耗材", materialQuery.category());
        assertEquals("纸巾", materialQuery.nameOrCode(),
                "关键字是「名称或编码模糊」的业务语义条件，具体 LIKE 拼法由适配器负责");
        // 排序（id desc）是适配器的固定口径，断言见 InventoryMaterialRepositoryAdapterTest。
    }

    @Test
    void blankFiltersAreIgnoredInsteadOfMatchingEmptyStrings() {
        stubMaterialPage();

        service.listMaterials(1L, 20L, null, "   ", null, "  ", TimeRange.none());

        assertNull(materialQuery.status(), "空白状态必须当作未传，不能去匹配空串");
        assertNull(materialQuery.category());
        assertNull(materialQuery.nameOrCode(), "空白关键字必须当作未传");
    }

    /** 物料创建时间闭区间按物料时间列过滤（统一 from/to 口径）。 */
    @Test
    void materialsApplyCreatedAtClosedRange() {
        stubMaterialPage();

        service.listMaterials(1L, 20L, null, null, null, null, TimeRangeParams.parse("2026-09-01", "2026-09-30"));

        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), materialQuery.createdFrom(),
                "起始端点必须按当天 00:00:00 收口");
        assertEquals(LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000), materialQuery.createdTo(),
                "结束端点必须按当天最后一毫秒收口（闭区间）；不用 LocalTime.MAX，避免被 DATETIME(3) 进位到次日");
    }

    /** 越权门店必须拒绝，且不查库。 */
    @Test
    void materialsRejectForeignStore() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.listMaterials(1L, 20L, 999L, null, null, null, TimeRange.none()));

        assertEquals("STORE_SCOPE_DENIED", error.getCode());
        verifyNoInteractions(materialRepository);
    }

    // —— 流水筛选 ——

    @Test
    void transactionsApplyTypeSourceAndClosedDateRange() {
        stubTransactionPage();

        service.listTransactions(1L, 20L, 7L, "RECEIPT", "RECEIPT", TimeRangeParams.parse("2026-09-01", "2026-09-30"), null);

        assertEquals(TENANT_ID, transactionQuery.tenantId());
        assertEquals(STORE_ID, transactionQuery.storeId());
        assertEquals(7L, transactionQuery.materialId());
        assertEquals("RECEIPT", transactionQuery.transactionType());
        assertEquals("RECEIPT", transactionQuery.sourceType());
        assertEquals(0L, transactionQuery.offset());
        assertEquals(20L, transactionQuery.limit());
        // 闭区间：起始日从 00:00:00 起，结束日收口到当天最后一毫秒（整天都能命中）
        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), transactionQuery.createdFrom(),
                "起始端点必须按当天 00:00:00 收口");
        assertEquals(LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000), transactionQuery.createdTo(),
                "结束端点必须按当天最后一毫秒收口（闭区间）；不用 LocalTime.MAX，避免被 DATETIME(3) 进位到次日");
        // 排序（id desc）与 created_at 过滤的 SQL 形状断言见 InventoryTransactionRepositoryAdapterTest。
    }

    /**
     * 时间参数的**解析与校验**已统一到 {@code TimeRangeParams}（全仓唯一实现），因此它不再属于本类：
     * <ul>
     *   <li>{@code from > to} / 格式非法 → {@code TimeRangeParamsTest} 断言 400 {@code TIME_RANGE_INVALID}；</li>
     *   <li>HTTP 层的 400 与「不落到服务层」→ {@code InventoryControllerWebTest} 断言。</li>
     * </ul>
     * 本类只保留「服务层拿到区间后怎么交给端口」的断言（见上）。
     */

    /** 关键字没命中任何物料：直接空页，不查流水（避免 {@code IN ()} 这种非法 SQL）。 */
    @Test
    void transactionsReturnEmptyPageWhenKeywordMatchesNoMaterial() {
        when(materialRepository.findIdsByNameOrCode(TENANT_ID, STORE_ID, "不存在")).thenReturn(List.of());

        Page<InventoryTransactionView> result =
                service.listTransactions(1L, 20L, null, null, null, TimeRangeParams.parse(null, null), "不存在");

        assertEquals(0L, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
        verifyNoInteractions(transactionRepository);
    }

    /** 关键字命中物料：把命中的 id 白名单交给端口（不逐行回查，N+1 由端口一次 IN 完成）。 */
    @Test
    void transactionsFilterByMatchedMaterialIdsWithoutPerRowLookup() {
        when(materialRepository.findIdsByNameOrCode(TENANT_ID, STORE_ID, "可乐")).thenReturn(List.of(11L));
        stubTransactionPage();

        service.listTransactions(1L, 20L, null, null, null, TimeRangeParams.parse(null, null), "可乐");

        assertEquals(List.of(11L), transactionQuery.materialIds(),
                "命中物料 id 必须作为白名单一次交给端口；空集合由本类提前返回空页");
        verify(materialRepository).findIdsByNameOrCode(TENANT_ID, STORE_ID, "可乐");
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
        when(materialRepository.findIdsByNameOrCode(TENANT_ID, STORE_ID, "可乐")).thenReturn(List.of(12L));
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

    /** 物料分页桩：记录下发给端口的查询条件，并回一页记录与命中总数。 */
    private void stubMaterialPage(InventoryMaterial... records) {
        when(materialRepository.findPage(any(MaterialQuery.class))).thenAnswer(invocation -> {
            materialQuery = invocation.getArgument(0);
            return new MaterialPage(List.of(records), records.length);
        });
        // 列表读路径会用一次 IN 查询回填结存/预占（非列字段，见 InventoryMaterialView）。
        when(stockRepository.findByMaterials(any(), any(), any())).thenReturn(List.of());
        when(materialRepository.findIdsByNameOrCode(any(), any(), any())).thenReturn(List.of());
    }

    /** 流水分页桩：同上。 */
    private void stubTransactionPage(InventoryTransaction... records) {
        when(transactionRepository.findPage(any(TransactionQuery.class))).thenAnswer(invocation -> {
            transactionQuery = invocation.getArgument(0);
            return new TransactionPage(List.of(records), records.length);
        });
    }

    private void stubCostSources(InventoryStock... stocks) {
        when(stockRepository.findByStore(TENANT_ID, STORE_ID)).thenReturn(List.of(stocks));
        List<InventoryMaterial> materials = java.util.Arrays.stream(stocks)
                .map(stock -> material(stock.materialId())).toList();
        when(materialRepository.findByIds(any())).thenReturn(materials);
    }

    private static InventoryStock stock(Long materialId, String onHand, String avgCost, String currency) {
        return new InventoryStock(20L, TENANT_ID, STORE_ID, materialId, new BigDecimal(onHand), BigDecimal.ZERO,
                new BigDecimal(avgCost), currency, 0);
    }

    private static InventoryMaterial material(Long id) {
        return new InventoryMaterial(id, TENANT_ID, STORE_ID, "M-" + id, "物料" + id, "耗材", "件", null,
                BigDecimal.ZERO, null, "USD", null, null, "ACTIVE", 0, LocalDateTime.now());
    }
}

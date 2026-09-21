package com.gvchat.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.currency.Currency;
import com.gvchat.infrastructure.currency.CurrencyContextHolder;
import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.MqProducer;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.infrastructure.time.TimeRangeParams.TimeRange;
import com.gvchat.infrastructure.time.TimeRangeParams;
import com.gvchat.platform.order.infra.mq.EventOutboxRelay;
import com.gvchat.platform.order.infra.persistence.po.InventoryMaterialPo;
import com.gvchat.platform.order.infra.persistence.po.InventoryTransactionPo;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 三个只读端点的**真库分页与筛选**集成测试（H2 + Flyway + MyBatis，仅 mock MQ/审计）。
 *
 * <p>存在的理由：分页要靠 MyBatis-Plus 的 {@code PaginationInnerInterceptor} 才会拼 LIMIT 与 count，
 * 缺插件时 {@code selectPage} 会「静默返回全表且 total=0」；Mockito 单测只能断言下发的 Page 参数，
 * 证明不了真实 SQL。这里真跑 SQL，守住：
 * <ol>
 *   <li>materials 分页信封（records/total/current/size）与 id desc 排序；</li>
 *   <li>materials 关键字/分类/状态筛选；</li>
 *   <li>transactions 类型/来源/关键字筛选与 {@code created_at} 闭区间（按天命中）；</li>
 *   <li>costs 分页只切物料行，total 与币种信封按全部命中行计算（翻页不漂移）。</li>
 * </ol>
 * 每个用例用独立门店，避免共享 H2 内存库时互相看见对方的数据。
 */
@SpringBootTest
@ActiveProfiles("test")
class InventoryPagingIntegrationTest {

  private static final long TENANT_ID = 1001L;
  private static final long STORE_MATERIAL_PAGE = 2801L;
  private static final long STORE_MATERIAL_FILTER = 2802L;
  private static final long STORE_TRANSACTION = 2803L;
  private static final long STORE_COST = 2804L;

  @MockitoBean
  private MqProducer mqProducer;

  @MockitoBean
  private MqConsumerFactory mqConsumerFactory;

  @MockitoBean
  private AuditClient auditClient;

  @MockitoBean
  private EventOutboxRelay eventOutboxRelay;

  @Autowired
  private InventoryApplicationService inventoryService;

  @Autowired
  private DataSource dataSource;

  @BeforeEach
  void setUpTenant() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_MATERIAL_PAGE, 0L, 0));
  }

  @AfterEach
  void tearDownContext() {
    TenantContextHolder.clear();
    CurrencyContextHolder.clear();
  }

  /** 物料分页：total 是全量命中条数，records 只给当前页，排序 id desc（分页插件真的拼了 LIMIT）。 */
  @Test
  void materialsPagingReturnsEnvelopeAndRealLimit() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_MATERIAL_PAGE, 0L, 0));
    List<Long> createdIds = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      createdIds.add(newMaterial(STORE_MATERIAL_PAGE, "分页物料" + i).getId());
    }

    Page<InventoryMaterialPo> first = inventoryService.listMaterials(1L, 2L, STORE_MATERIAL_PAGE, "ACTIVE", null, null, TimeRange.none());
    assertEquals(3L, first.getTotal(), "total 必须是该门店的命中物料总数，而不是当前页条数（分页插件未注册时为 0）");
    assertEquals(2, first.getRecords().size(), "pageSize=2 时第一页必须只返回 2 条（未注册插件会返回全表）");
    assertEquals(1L, first.getCurrent());
    assertEquals(2L, first.getSize());
    assertTrue(first.getRecords().get(0).getId() > first.getRecords().get(1).getId(), "必须按 id desc 排序");
    assertEquals(createdIds.get(2), first.getRecords().get(0).getId(), "最新的物料排在第一页第一条");

    Page<InventoryMaterialPo> second = inventoryService.listMaterials(2L, 2L, STORE_MATERIAL_PAGE, "ACTIVE", null, null, TimeRange.none());
    assertEquals(3L, second.getTotal());
    assertEquals(1, second.getRecords().size());
    assertEquals(createdIds.get(0), second.getRecords().get(0).getId());
  }

  /** 物料筛选：关键字命中名称或编码，分类/状态精确匹配，且能相互叠加。 */
  @Test
  void materialsFilterByKeywordCategoryAndStatus() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_MATERIAL_FILTER, 0L, 0));
    InventoryMaterialPo cola = newMaterial(STORE_MATERIAL_FILTER, "可乐 330ml", "饮品");
    newMaterial(STORE_MATERIAL_FILTER, "毛巾", "耗材");

    Page<InventoryMaterialPo> byName = inventoryService.listMaterials(1L, 20L, STORE_MATERIAL_FILTER, null, null, "可乐", TimeRange.none());
    assertEquals(1L, byName.getTotal());
    assertEquals(cola.getId(), byName.getRecords().get(0).getId());

    Page<InventoryMaterialPo> byCode =
        inventoryService.listMaterials(1L, 20L, STORE_MATERIAL_FILTER, null, null, cola.getMaterialCode(), TimeRange.none());
    assertEquals(1L, byCode.getTotal(), "关键字也必须能命中物料编码");

    assertEquals(1L, inventoryService.listMaterials(1L, 20L, STORE_MATERIAL_FILTER, null, "饮品", null, TimeRange.none()).getTotal());
    assertEquals(0L, inventoryService.listMaterials(1L, 20L, STORE_MATERIAL_FILTER, "INACTIVE", null, null, TimeRange.none()).getTotal());
    assertEquals(0L, inventoryService.listMaterials(1L, 20L, STORE_MATERIAL_FILTER, null, null, "不存在", TimeRange.none()).getTotal());
  }

  /**
   * 物料创建时间区间（真库）：`created_at` 是建档时写入的 `now()`，因此「今天」的闭区间必须命中，
   * 「昨天」的单日区间必须为 0 —— 两端都是日期形态，直接验证 `from`/`to` 的整天收口。
   *
   * <p>`total` 与 `records` 由同一次 `selectPage` 得出，所以过滤后的 total 必须是命中数而不是门店全量。
   */
  @Test
  void materialsFilterByCreatedAtClosedDayRangeUsesRealSql() {
    Long storeId = STORE_MATERIAL_FILTER + 1;
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, storeId, 0L, 0));
    newMaterial(storeId, "今天建的物料A");
    newMaterial(storeId, "今天建的物料B");

    LocalDate today = LocalDate.now();
    LocalDate yesterday = today.minusDays(1);

    // 今天（闭区间：from 00:00:00 ~ to 23:59:59.999）
    Page<InventoryMaterialPo> sameDay = inventoryService.listMaterials(1L, 20L, storeId, "ACTIVE", null, null,
            TimeRangeParams.parse(today.toString(), today.toString()));
    assertEquals(2L, sameDay.getTotal(), "当天闭合区间必须命中当天建档的全部物料");
    assertEquals(2, sameDay.getRecords().size(), "records 与 total 口径必须一致");

    // 昨天：一条都不该命中
    assertEquals(0L, inventoryService.listMaterials(1L, 20L, storeId, "ACTIVE", null, null,
            TimeRangeParams.parse(yesterday.toString(), yesterday.toString())).getTotal(),
            "收口值不得溢出到相邻日期（否则昨天会命中今天的物料）");

    // 只给上界 = 今天：命中；只给下界 = 明天：不命中
    assertEquals(2L, inventoryService.listMaterials(1L, 20L, storeId, "ACTIVE", null, null,
            TimeRangeParams.parse(null, today.toString())).getTotal());
    assertEquals(0L, inventoryService.listMaterials(1L, 20L, storeId, "ACTIVE", null, null,
            TimeRangeParams.parse(today.plusDays(1).toString(), null)).getTotal());

    // 不筛：同样是 2 条（对照组，证明上面的 0 不是「查询坏掉」）
    assertEquals(2L, inventoryService.listMaterials(1L, 20L, storeId, "ACTIVE", null, null,
            TimeRange.none()).getTotal());
  }

  /** 流水筛选：类型/来源/关键字/日期闭区间；结束日整天可命中，区间外为 0。 */
  @Test
  void transactionsFilterByTypeSourceKeywordAndClosedDateRange() throws Exception {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_TRANSACTION, 0L, 0));
    InventoryMaterialPo cola = newMaterial(STORE_TRANSACTION, "可乐", "饮品");
    InventoryMaterialPo towel = newMaterial(STORE_TRANSACTION, "毛巾", "耗材");
    inventoryService.changeStock(cola.getId(), new BigDecimal("5"), "RECEIPT", "RECEIPT", null,
        "采购入库", "paging-receipt-" + cola.getId(), null);
    inventoryService.changeStock(towel.getId(), new BigDecimal("3"), "RECEIPT", "RECEIPT", null,
        "采购入库", "paging-receipt-" + towel.getId(), null);
    inventoryService.changeStock(cola.getId(), new BigDecimal("2"), "CONSUME", "ORDER_ITEM", "9",
        "点单扣减", "paging-consume-" + cola.getId());

    assertEquals(3L, inventoryService
        .listTransactions(1L, 20L, null, null, null, TimeRangeParams.parse(null, null), null).getTotal());
    assertEquals(2L, inventoryService
        .listTransactions(1L, 20L, null, "RECEIPT", null, TimeRangeParams.parse(null, null), null).getTotal());
    assertEquals(1L, inventoryService
        .listTransactions(1L, 20L, null, null, "ORDER_ITEM", TimeRangeParams.parse(null, null), null).getTotal());
    assertEquals(2L, inventoryService
        .listTransactions(1L, 20L, cola.getId(), null, null, TimeRangeParams.parse(null, null), null).getTotal(),
        "可乐有采购入库 + 销售出库两条流水");
    assertEquals(1L, inventoryService
        .listTransactions(1L, 20L, null, null, null, TimeRangeParams.parse(null, null), "毛巾").getTotal(), "关键字按物料名过滤");
    assertEquals(0L, inventoryService
        .listTransactions(1L, 20L, null, null, null, TimeRangeParams.parse(null, null), "不存在").getTotal());

    // 把流水钉到固定日期，验证闭区间按天命中（不依赖运行时钟）：可乐两条 09-14，毛巾一条 09-13
    rawUpdateCreatedAt(STORE_TRANSACTION, cola.getId(), "2026-09-14 12:00:00");
    rawUpdateCreatedAt(STORE_TRANSACTION, towel.getId(), "2026-09-13 08:30:00");
    assertEquals(2L, inventoryService
        .listTransactions(1L, 20L, null, null, null, TimeRangeParams.parse("2026-09-14", "2026-09-14"), null).getTotal(),
        "同一天作为起止必须命中午夜到 23:59:59.999999999 之间的全部流水");
    assertEquals(1L, inventoryService
        .listTransactions(1L, 20L, null, null, null, TimeRangeParams.parse("2026-09-13", "2026-09-13"), null).getTotal());
    assertEquals(3L, inventoryService
        .listTransactions(1L, 20L, null, null, null, TimeRangeParams.parse("2026-09-13", "2026-09-14"), null).getTotal(),
        "跨天区间必须把两天的流水都算进来");
    assertEquals(0L, inventoryService
        .listTransactions(1L, 20L, null, null, null, TimeRangeParams.parse("2026-09-15", null), null).getTotal(),
        "晚于全部流水的起始端点必须为空");
    assertEquals(0L, inventoryService
        .listTransactions(1L, 20L, null, null, null, TimeRangeParams.parse(null, "2026-09-12"), null).getTotal(),
        "早于全部流水的结束端点必须为空（结束日只覆盖当天，不外溢到次日）");
    assertEquals(2L, inventoryService
        .listTransactions(1L, 20L, null, null, null, TimeRangeParams.parse("2026-09-14T00:00:00", null), null).getTotal(),
        "同时接受 yyyy-MM-ddTHH:mm:ss 作为起始端点");
    assertEquals(2L, inventoryService
        .listTransactions(1L, 20L, null, null, null, TimeRangeParams.parse("2026-09-14T00:00:00", "2026-09-14T23:59:59"), null).getTotal(),
        "同时接受 yyyy-MM-ddTHH:mm:ss 作为起止端点（按精确时刻解析，不再按整天收口）");
    assertEquals(3L, inventoryService
        .listTransactions(1L, 20L, null, null, null, TimeRangeParams.parse(null, "2026-09-14T23:59:59"), null).getTotal(),
        "只给结束端点时，早于该时刻的全部流水都要命中");

    Page<InventoryTransactionPo> page = inventoryService
        .listTransactions(1L, 1L, null, "RECEIPT", null, TimeRangeParams.parse(null, null), null);
    assertEquals(2L, page.getTotal(), "total 必须是全部命中流水数");
    assertEquals(1, page.getRecords().size(), "pageSize=1 时第一页只有 1 条");

    Page<InventoryTransactionPo> secondPage = inventoryService
        .listTransactions(2L, 1L, null, "RECEIPT", null, TimeRangeParams.parse(null, null), null);
    assertEquals(2L, secondPage.getTotal(), "翻页后 total 不随页码变化");
    assertEquals(1, secondPage.getRecords().size(), "pageSize=1 时第二页也只有 1 条");
    assertFalse(secondPage.getRecords().get(0).getId().equals(page.getRecords().get(0).getId()),
        "第二页必须是另一条流水（偏移真的生效）");
  }

  /** 成本：分页只切物料行，total 与币种信封按全部命中行计算（翻页不漂移）。 */
  @Test
  void costsPageRowsButEnvelopeCoversAllMatchedRows() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_COST, 0L, 0));
    CurrencyContextHolder.set(Currency.CNY);
    InventoryMaterialPo cola = newMaterial(STORE_COST, "可乐", "饮品", new BigDecimal("300"));
    InventoryMaterialPo towel = newMaterial(STORE_COST, "毛巾", "耗材", new BigDecimal("1250"));
    inventoryService.changeStock(cola.getId(), new BigDecimal("2"), "RECEIPT", "RECEIPT", null,
        "入库", "cost-page-cola", null);
    inventoryService.changeStock(towel.getId(), BigDecimal.ONE, "RECEIPT", "RECEIPT", null,
        "入库", "cost-page-towel", null);

    InventoryApplicationService.InventoryCostReport first = inventoryService.inventoryCosts(1L, 1L, STORE_COST, null);
    assertEquals(2L, first.getTotal(), "total = 命中物料条数");
    assertEquals(1, first.getRecords().size(), "pageSize=1 时当前页只有 1 行");
    assertEquals("CNY", first.getCurrencyCode(), "单币种信封按全部命中行给出币种");
    assertFalse(first.isMixedCurrency());

    InventoryApplicationService.InventoryCostReport second = inventoryService.inventoryCosts(2L, 1L, STORE_COST, null);
    assertEquals(2L, second.getTotal());
    assertEquals(1, second.getRecords().size(), "第 2 页拿到另一行（分页真的生效）");
    assertFalse(second.getRecords().get(0).materialId().equals(first.getRecords().get(0).materialId()));
    assertEquals("CNY", second.getCurrencyCode(), "翻页后信封不漂移");

    InventoryApplicationService.InventoryCostRow colaRow = List.of(first, second).stream()
        .flatMap(report -> report.getRecords().stream())
        .filter(row -> row.materialId().equals(cola.getId())).findFirst().orElseThrow();
    assertEquals(0, colaRow.inventoryCost().compareTo(new BigDecimal("600")), "2 件 × 300 = 600");

    assertEquals(1L, inventoryService.inventoryCosts(1L, 20L, STORE_COST, "毛巾").getTotal(), "关键字按物料名过滤");
    assertEquals(0L, inventoryService.inventoryCosts(1L, 20L, STORE_COST, "不存在").getTotal());
  }

  // —— 辅助 ——

  private InventoryMaterialPo newMaterial(long storeId, String name) {
    return newMaterial(storeId, name, "耗材");
  }

  private InventoryMaterialPo newMaterial(long storeId, String name, String category) {
    return newMaterial(storeId, name, category, null);
  }

  private InventoryMaterialPo newMaterial(long storeId, String name, String category, BigDecimal purchasePrice) {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, storeId, 0L, 0));
    InventoryMaterialPo material = inventoryService.createMaterial(new InventoryApplicationService.MaterialCommand(
        storeId, "PAGE-" + System.nanoTime(), name, category, "件", BigDecimal.ZERO, purchasePrice, null, null, null));
    assertNotNull(material.getId());
    return material;
  }

  /** 绕过 ORM 钉住流水的发生时刻（{@code created_at}），用于验证按天闭区间。 */
  private void rawUpdateCreatedAt(long storeId, Long materialId, String timestamp) throws Exception {
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("UPDATE ord_inventory_transaction SET created_at = '" + timestamp
          + "' WHERE tenant_id = " + TENANT_ID + " AND store_id = " + storeId + " AND material_id = " + materialId);
    }
  }
}

package com.gvchat.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.currency.Currency;
import com.gvchat.infrastructure.currency.CurrencyContextHolder;
import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.MqProducer;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.infra.mq.EventOutboxRelay;
import com.gvchat.platform.order.infra.persistence.mapper.InventoryStockMapper;
import com.gvchat.platform.order.infra.persistence.mapper.InventoryTransactionMapper;
import com.gvchat.platform.order.infra.persistence.po.InventoryMaterialPo;
import com.gvchat.platform.order.infra.persistence.po.InventoryStockPo;
import com.gvchat.platform.order.infra.persistence.po.InventoryTransactionPo;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 入库批次单价 → 移动加权平均成本 → 库存成本 的集成测试（真跑 H2 + Flyway + MyBatis，仅 mock MQ/审计）。
 *
 * <p>覆盖 V24__ord_inventory_moving_average_cost.sql 引入的
 * {@code ord_inventory_transaction.unit_cost/total_cost/currency_code} 与
 * {@code ord_inventory_stock.avg_cost/currency_code}：
 * <ol>
 *   <li>两次不同单价入库 → 平均成本与库存成本正确（含定点舍入）；</li>
 *   <li>批次单价缺省取物料采购价、显式传入优先、显式 0 视为未填；</li>
 *   <li>出库不改变平均成本，只按平均成本结转发生额；</li>
 *   <li>onHand&lt;=0 边界直接取批次单价，不参与加权；</li>
 *   <li>跨币种不静默混合（拒绝并给出明确错误码）；</li>
 *   <li>并发入库不丢失更新（数量与成本金额都不丢）；</li>
 *   <li>历史数据（NULL 批次单价 / 0 成本基准）不回归；</li>
 *   <li>库存成本查询按门店/物料/币种给出口径。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class InventoryMovingAverageCostTest {

  private static final long TENANT_ID = 1001L;
  /** 每个用例独立门店，避免共享 H2 内存库时互相看见对方的库存行。 */
  private static final long STORE_WEIGHTED = 2701L;
  private static final long STORE_DEFAULT_PRICE = 2702L;
  private static final long STORE_CONSUME = 2703L;
  private static final long STORE_EDGE = 2704L;
  private static final long STORE_CURRENCY = 2705L;
  private static final long STORE_CONCURRENT = 2706L;
  private static final long STORE_LEGACY = 2707L;
  private static final long STORE_COST_REPORT = 2708L;

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
  private InventoryStockMapper stockMapper;

  @Autowired
  private InventoryTransactionMapper transactionMapper;

  @Autowired
  private DataSource dataSource;

  @BeforeEach
  void setUpTenant() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_WEIGHTED, 0L, 0));
  }

  @AfterEach
  void tearDownContext() {
    TenantContextHolder.clear();
    CurrencyContextHolder.clear();
  }

  /** 两次不同单价入库：avg = (10×300 + 10×500) / 20 = 400，库存成本 = 20×400 = 8000。 */
  @Test
  void twoReceiptsAtDifferentUnitPricesProduceWeightedAverageAndInventoryCost() {
    InventoryMaterialPo material = newMaterial(STORE_WEIGHTED, "加权平均", new BigDecimal("300"));

    InventoryTransactionPo first = inventoryService.changeStock(material.getId(), new BigDecimal("10"), "RECEIPT",
        "RECEIPT", null, "首批入库", key("ma-first"), null);
    assertEquals(0, first.getUnitCost().compareTo(new BigDecimal("300")), "未传批次单价时缺省取物料采购价");
    assertEquals(0, first.getTotalCost().compareTo(new BigDecimal("3000")), "批次发生额 = 数量 × 批次单价");
    assertEquals("USD", first.getCurrencyCode());
    assertEquals(0, avgCostOf(STORE_WEIGHTED, material.getId()).compareTo(new BigDecimal("300")));
    assertEquals(0, inventoryCostOf(STORE_WEIGHTED, material.getId()).compareTo(new BigDecimal("3000")));

    InventoryTransactionPo second = inventoryService.changeStock(material.getId(), new BigDecimal("10"), "RECEIPT",
        "RECEIPT", null, "第二批入库", key("ma-second"), new BigDecimal("500"));
    assertEquals(0, second.getUnitCost().compareTo(new BigDecimal("500")));
    assertEquals(0, second.getTotalCost().compareTo(new BigDecimal("5000")));
    assertEquals(0, onHandOf(STORE_WEIGHTED, material.getId()).compareTo(new BigDecimal("20")));
    assertEquals(0, avgCostOf(STORE_WEIGHTED, material.getId()).compareTo(new BigDecimal("400")),
        "(10×300 + 10×500) / 20 = 400");
    assertEquals(0, inventoryCostOf(STORE_WEIGHTED, material.getId()).compareTo(new BigDecimal("8000")),
        "库存成本 = 结存数量 × 移动加权平均成本");
  }

  /** 批次单价取值链：显式传入优先，显式 0 与 null 同义（未填 → 物料采购价）。 */
  @Test
  void receiptUnitCostPrefersExplicitValueAndTreatsZeroAsNotProvided() {
    InventoryMaterialPo material = newMaterial(STORE_DEFAULT_PRICE, "取值链", new BigDecimal("350"));

    InventoryTransactionPo fromMaterial = inventoryService.changeStock(material.getId(), BigDecimal.ONE, "RECEIPT",
        "RECEIPT", null, "缺省", key("fallback"), null);
    assertEquals(0, fromMaterial.getUnitCost().compareTo(new BigDecimal("350")));
    assertEquals("USD", fromMaterial.getCurrencyCode(), "缺省取物料采购价时必须带上物料币种快照");

    InventoryTransactionPo explicit = inventoryService.changeStock(material.getId(), BigDecimal.ONE, "RECEIPT",
        "RECEIPT", null, "显式", key("explicit"), new BigDecimal("700"));
    assertEquals(0, explicit.getUnitCost().compareTo(new BigDecimal("700")), "显式传入必须优先于物料采购价");

    InventoryTransactionPo zero = inventoryService.changeStock(material.getId(), BigDecimal.ONE, "RECEIPT",
        "RECEIPT", null, "零视为未填", key("zero"), BigDecimal.ZERO);
    assertEquals(0, zero.getUnitCost().compareTo(new BigDecimal("350")), "0 = 未填（与采购价同款三态约定）");

    // (1×350 + 1×700 + 1×350) / 3 = 466.666667（定点 6 位 HALF_UP）
    assertEquals(0, avgCostOf(STORE_DEFAULT_PRICE, material.getId()).compareTo(new BigDecimal("466.666667")));
  }

  /** 出库/消耗不改变平均成本，只按平均成本结转发生额（负号与 quantity_delta 同号）。 */
  @Test
  void consumeKeepsAverageCostAndRecordsAmountAtAverageCost() {
    InventoryMaterialPo material = newMaterial(STORE_CONSUME, "出库结转", new BigDecimal("300"));
    inventoryService.changeStock(material.getId(), new BigDecimal("10"), "RECEIPT", "RECEIPT", null,
        "入库", key("consume-receipt"), null);

    InventoryTransactionPo consume = inventoryService.changeStock(material.getId(), new BigDecimal("4"), "CONSUME",
        "ORDER_ITEM", "77", "加项扣减", key("consume"));

    assertEquals(0, consume.getUnitCost().compareTo(new BigDecimal("300")), "出库单价 = 当前移动加权平均成本");
    assertEquals(0, consume.getTotalCost().compareTo(new BigDecimal("-1200")), "发生额 = 数量 × 平均成本，出库为负");
    assertEquals(0, consume.getQuantityDelta().compareTo(new BigDecimal("-4")));
    assertEquals(0, avgCostOf(STORE_CONSUME, material.getId()).compareTo(new BigDecimal("300")),
        "出库绝不重估平均成本");
    assertEquals(0, inventoryCostOf(STORE_CONSUME, material.getId()).compareTo(new BigDecimal("1800")),
        "剩余 6 件 × 300 = 1800");
  }

  /**
   * 边界：结存数量 &lt;= 0 时不做加权，直接取该批次单价。
   * 用脏数据（on_hand_qty = -2、avg_cost = 999）区分两条路径：加权公式会算出
   * ((-2)×999 + 3×400) / 1 = -798，直接取批次价则是 400。
   */
  @Test
  void nonPositiveOnHandTakesBatchUnitPriceInsteadOfWeighting() throws Exception {
    InventoryMaterialPo material = newMaterial(STORE_EDGE, "零存量边界", null);
    rawInsertStock(STORE_EDGE, material.getId(), new BigDecimal("-2"), new BigDecimal("999"), "USD");

    InventoryTransactionPo receipt = inventoryService.changeStock(material.getId(), new BigDecimal("3"), "RECEIPT",
        "RECEIPT", null, "重新建账", key("edge"), new BigDecimal("400"));

    assertEquals(0, receipt.getUnitCost().compareTo(new BigDecimal("400")));
    assertEquals(0, avgCostOf(STORE_EDGE, material.getId()).compareTo(new BigDecimal("400")),
        "onHand <= 0 没有存量可加权，平均成本直接等于批次单价");
    assertEquals(0, onHandOf(STORE_EDGE, material.getId()).compareTo(BigDecimal.ONE));
  }

  /** 跨币种不静默混合：旧成本基准非 0 且币种不同 → 拒绝入库，库存与成本都不动。 */
  @Test
  void receiptInAnotherCurrencyIsRejectedInsteadOfBlendingAverageCost() {
    CurrencyContextHolder.set(Currency.CNY);
    InventoryMaterialPo material = newMaterial(STORE_CURRENCY, "跨币种", new BigDecimal("300"));
    inventoryService.changeStock(material.getId(), new BigDecimal("5"), "RECEIPT", "RECEIPT", null,
        "CNY 入库", key("cny-receipt"), null);
    assertEquals("CNY", currencyOf(STORE_CURRENCY, material.getId()));

    CurrencyContextHolder.set(Currency.USD);
    String usdReceiptKey = key("usd-receipt");
    BusinessException error = assertThrows(BusinessException.class,
        () -> inventoryService.changeStock(material.getId(), new BigDecimal("5"), "RECEIPT", "RECEIPT", null,
            "USD 入库", usdReceiptKey, new BigDecimal("400")));

    assertEquals("INVENTORY_CURRENCY_MISMATCH", error.getCode());
    assertEquals(0, onHandOf(STORE_CURRENCY, material.getId()).compareTo(new BigDecimal("5")),
        "被拒绝的入库不能改库存数量");
    assertEquals(0, avgCostOf(STORE_CURRENCY, material.getId()).compareTo(new BigDecimal("300")),
        "被拒绝的入库不能改平均成本");
    assertNull(transactionMapper.findByIdempotency(TENANT_ID, usdReceiptKey), "被拒绝的入库不能留下流水");
  }

  /** 并发入库不丢失更新：6 个批次各自单价不同，最终数量与成本金额必须与「全部成功」一致。 */
  @Test
  void concurrentReceiptsKeepWeightedAverageConsistent() throws Exception {
    InventoryMaterialPo material = newMaterial(STORE_CONCURRENT, "并发入库", null);
    inventoryService.changeStock(material.getId(), BigDecimal.ONE, "RECEIPT", "RECEIPT", null,
        "建行", key("conc-seed"), new BigDecimal("100"));

    int threads = 6;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger success = new AtomicInteger();
    AtomicInteger unexpected = new AtomicInteger();
    for (int i = 0; i < threads; i++) {
      final int index = i;
      pool.submit(() -> {
        try {
          TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_CONCURRENT, 0L, 0));
          ready.countDown();
          start.await(30, TimeUnit.SECONDS);
          inventoryService.changeStock(material.getId(), BigDecimal.ONE, "RECEIPT", "RECEIPT", null,
              "并发批次 " + index, key("conc-" + index), new BigDecimal(200 + index * 100));
          success.incrementAndGet();
        } catch (BusinessException e) {
          unexpected.incrementAndGet();
        } catch (Exception e) {
          unexpected.incrementAndGet();
        } finally {
          TenantContextHolder.clear();
          done.countDown();
        }
      });
    }
    assertTrue(ready.await(30, TimeUnit.SECONDS), "并发线程未就绪");
    start.countDown();
    assertTrue(done.await(60, TimeUnit.SECONDS), "并发入库未在超时时间内结束");
    pool.shutdownNow();

    assertEquals(threads, success.get(), "入库不因并发被拒绝");
    assertEquals(0, unexpected.get(), "并发入库不应出现意外异常");
    BigDecimal onHand = onHandOf(STORE_CONCURRENT, material.getId());
    assertEquals(0, onHand.compareTo(new BigDecimal(threads + 1)), "每个批次都必须加到库存上（不丢数量）");
    // 种子 100 + (200+300+400+500+600+700) = 2800；每步定点舍入的累计误差远小于 0.001，
    // 任何「丢失更新」都会让成本金额成百上千地偏离。
    BigDecimal totalValue = avgCostOf(STORE_CONCURRENT, material.getId()).multiply(onHand);
    assertTrue(totalValue.subtract(new BigDecimal("2800")).abs().compareTo(new BigDecimal("0.001")) < 0,
        "加权平均成本必须等于全部批次金额之和 / 总数量，实际 " + totalValue);
  }

  /** 历史数据不回归：升级前的库存行（有结存、0 成本基准）与历史流水（NULL 批次单价）照常可用。 */
  @Test
  void legacyRowsWithoutCostBasisKeepWorking() throws Exception {
    InventoryMaterialPo material = newMaterial(STORE_LEGACY, "历史数据", null);
    String legacyKey = "legacy-" + System.nanoTime();
    rawInsertLegacyTransaction(STORE_LEGACY, material.getId(), legacyKey);
    rawInsertStock(STORE_LEGACY, material.getId(), new BigDecimal("3"), BigDecimal.ZERO, "USD");

    InventoryTransactionPo receipt = inventoryService.changeStock(material.getId(), BigDecimal.ONE, "RECEIPT",
        "RECEIPT", null, "升级后首次入库", key("legacy-receipt"), new BigDecimal("200"));

    assertEquals(0, receipt.getUnitCost().compareTo(new BigDecimal("200")));
    assertEquals(0, onHandOf(STORE_LEGACY, material.getId()).compareTo(new BigDecimal("4")));
    // 历史结存 3 件无成本基准（0），按既定公式 (3×0 + 1×200) / 4 = 50 稀释，不做追溯重估。
    assertEquals(0, avgCostOf(STORE_LEGACY, material.getId()).compareTo(new BigDecimal("50")));

    InventoryTransactionPo legacy = transactionMapper.findByIdempotency(TENANT_ID, legacyKey);
    assertNotNull(legacy, "历史流水必须仍可按幂等键读回");
    assertNull(legacy.getUnitCost(), "升级前未采集批次单价，保持 NULL 而不是 0");
    assertNull(legacy.getTotalCost());
    assertEquals(2, transactionMapper.selectByStore(TENANT_ID, STORE_LEGACY).size(),
        "历史流水 + 升级后新流水都必须可读");
  }

  /** 库存成本查询：结存数量 × 移动加权平均成本，按门店/物料/币种。 */
  @Test
  void inventoryCostsReportsPerMaterialCostInItsOwnCurrency() {
    CurrencyContextHolder.set(Currency.CNY);
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_COST_REPORT, 0L, 0));
    InventoryMaterialPo cola = newMaterial(STORE_COST_REPORT, "可乐", new BigDecimal("300"));
    InventoryMaterialPo towel = newMaterial(STORE_COST_REPORT, "毛巾", new BigDecimal("1250"));
    inventoryService.changeStock(cola.getId(), new BigDecimal("2"), "RECEIPT", "RECEIPT", null,
        "入库", key("cost-cola"), null);
    inventoryService.changeStock(towel.getId(), BigDecimal.ONE, "RECEIPT", "RECEIPT", null,
        "入库", key("cost-towel"), null);

    InventoryApplicationService.InventoryCostReport report =
        inventoryService.inventoryCosts(1L, InventoryApplicationService.MAX_PAGE_SIZE, STORE_COST_REPORT, null);

    assertEquals(STORE_COST_REPORT, report.getStoreId());
    assertEquals("CNY", report.getCurrencyCode());
    assertFalse(report.isMixedCurrency());
    assertEquals(2L, report.getTotal(), "total = 命中物料条数（不是当前页条数）");
    InventoryApplicationService.InventoryCostRow colaRow = report.getRecords().stream()
        .filter(row -> row.materialId().equals(cola.getId())).findFirst().orElseThrow();
    assertEquals(0, colaRow.onHandQty().compareTo(new BigDecimal("2")));
    assertEquals(0, colaRow.avgCost().compareTo(new BigDecimal("300")));
    assertEquals(0, colaRow.inventoryCost().compareTo(new BigDecimal("600")));
    assertEquals("CNY", colaRow.currencyCode());
    assertEquals("可乐", colaRow.materialName());
    InventoryApplicationService.InventoryCostRow towelRow = report.getRecords().stream()
        .filter(row -> row.materialId().equals(towel.getId())).findFirst().orElseThrow();
    assertEquals(0, towelRow.inventoryCost().compareTo(new BigDecimal("1250")));
  }

  /** 门店越权：传别的门店必须 STORE_SCOPE_DENIED，不能读到别的门店成本。 */
  @Test
  void inventoryCostsRejectsForeignStore() {
    BusinessException error = assertThrows(BusinessException.class,
        () -> inventoryService.inventoryCosts(1L, 20L, 999999L, null));

    assertEquals("STORE_SCOPE_DENIED", error.getCode());
  }

  // —— 辅助 ——

  private String key(String prefix) {
    return prefix + "-" + System.nanoTime();
  }

  private InventoryMaterialPo newMaterial(long storeId, String name, BigDecimal purchasePrice) {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, storeId, 0L, 0));
    return inventoryService.createMaterial(new InventoryApplicationService.MaterialCommand(
        storeId, "INV-" + System.nanoTime(), name, "耗材", "件", BigDecimal.ZERO, purchasePrice, null, null, null));
  }

  private BigDecimal onHandOf(long storeId, Long materialId) {
    InventoryStockPo stock = stockMapper.selectOne(new LambdaQueryWrapper<InventoryStockPo>()
        .eq(InventoryStockPo::getTenantId, TENANT_ID).eq(InventoryStockPo::getStoreId, storeId)
        .eq(InventoryStockPo::getMaterialId, materialId));
    assertNotNull(stock, "库存行必须存在");
    return stock.getOnHandQty();
  }

  private BigDecimal avgCostOf(long storeId, Long materialId) {
    InventoryStockPo stock = stockMapper.selectOne(new LambdaQueryWrapper<InventoryStockPo>()
        .eq(InventoryStockPo::getTenantId, TENANT_ID).eq(InventoryStockPo::getStoreId, storeId)
        .eq(InventoryStockPo::getMaterialId, materialId));
    assertNotNull(stock, "库存行必须存在");
    return stock.getAvgCost();
  }

  private String currencyOf(long storeId, Long materialId) {
    InventoryStockPo stock = stockMapper.selectOne(new LambdaQueryWrapper<InventoryStockPo>()
        .eq(InventoryStockPo::getTenantId, TENANT_ID).eq(InventoryStockPo::getStoreId, storeId)
        .eq(InventoryStockPo::getMaterialId, materialId));
    assertNotNull(stock, "库存行必须存在");
    return stock.getCurrencyCode();
  }

  private BigDecimal inventoryCostOf(long storeId, Long materialId) {
    return avgCostOf(storeId, materialId).multiply(onHandOf(storeId, materialId));
  }

  /** 绕过 ORM 造「升级前形态」的库存行：有结存、无成本基准（avg_cost = 0）或脏数据。 */
  private void rawInsertStock(long storeId, Long materialId, BigDecimal onHand, BigDecimal avgCost, String currency)
      throws Exception {
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO ord_inventory_stock (tenant_id, store_id, material_id, on_hand_qty, "
          + "reserved_qty, avg_cost, currency_code, version, created_at, updated_at) VALUES ("
          + TENANT_ID + ", " + storeId + ", " + materialId + ", " + onHand + ", 0, " + avgCost + ", '"
          + currency + "', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }
  }

  /** 绕过 ORM 造「升级前形态」的流水：unit_cost/total_cost 为 NULL（历史行本就无批次单价）。 */
  private void rawInsertLegacyTransaction(long storeId, Long materialId, String idempotencyKey) throws Exception {
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO ord_inventory_transaction (tenant_id, store_id, material_id, "
          + "transaction_type, quantity_delta, quantity_before, quantity_after, currency_code, source_type, "
          + "idempotency_key, created_at) VALUES (" + TENANT_ID + ", " + storeId + ", " + materialId
          + ", 'RECEIPT', 3, 0, 3, 'USD', 'RECEIPT', '" + idempotencyKey + "', CURRENT_TIMESTAMP)");
    }
  }
}

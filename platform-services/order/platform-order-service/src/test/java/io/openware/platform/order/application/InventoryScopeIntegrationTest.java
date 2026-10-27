package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.MqProducer;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.platform.order.api.dto.InventoryMaterialView;
import io.openware.platform.order.api.dto.InventoryTransactionView;
import io.openware.platform.order.domain.inventory.model.InventoryScope;
import io.openware.platform.order.domain.inventory.model.InventoryStock;
import io.openware.platform.order.domain.inventory.model.InventoryTransaction;
import io.openware.platform.order.domain.inventory.model.InventoryWarehouse;
import io.openware.platform.order.domain.inventory.repository.InventoryStockRepository;
import io.openware.platform.order.domain.inventory.repository.InventoryTransactionRepository;
import io.openware.platform.order.domain.inventory.repository.InventoryTransactionRepository.TransactionQuery;
import io.openware.platform.order.domain.inventory.repository.InventoryWarehouseRepository;
import io.openware.platform.order.infra.mq.EventOutboxRelay;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
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
 * 库存作用域隔离集成测试（真跑 H2 + Flyway 基线 + MyBatis，仅 mock MQ/审计）：
 *
 * <ol>
 *   <li><b>作用域快照</b>：酒店门店与 KTV 门店（同租户、不同组织/业态）的余额与流水分别落
 *       {@code organization_id / business_type / warehouse_id}，一店多仓时余额按仓隔离；</li>
 *   <li><b>不串数据</b>：KTV 上下文读不到酒店门店的物料/流水，反之亦然；可点性按门店跨仓汇总；</li>
 *   <li><b>写入校验</b>：请求声明的组织/业态与签名门店上下文不一致 → 422；</li>
 *   <li><b>幂等作用域</b>：同一个幂等键不能跨门店/业态重放（INVENTORY_SCOPE_MISMATCH）。</li>
 * </ol>
 *
 * <p>每个用例使用独立门店 id，避免共享 H2 内存库时互相看见对方的数据。
 */
@SpringBootTest
@ActiveProfiles("test")
class InventoryScopeIntegrationTest {

  private static final long TENANT_ID = 3001L;
  private static final long KTV_ORG = 11L;
  private static final long HOTEL_ORG = 12L;

  /** 用例 1：酒店 / KTV 作用域隔离。 */
  private static final long KTV_STORE = 6101L;
  private static final long HOTEL_STORE = 6102L;
  /** 用例 2：一店多仓。 */
  private static final long MULTI_WAREHOUSE_STORE = 6111L;
  /** 用例 3：请求声明校验。 */
  private static final long CLAIM_STORE = 6121L;
  /** 用例 4：跨作用域幂等重放。 */
  private static final long REPLAY_KTV_STORE = 6131L;
  private static final long REPLAY_HOTEL_STORE = 6132L;

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
  private InventoryStockRepository stockRepository;

  @Autowired
  private InventoryTransactionRepository transactionRepository;

  @Autowired
  private InventoryWarehouseRepository warehouseRepository;

  @Autowired
  private DataSource dataSource;

  @BeforeEach
  void seedStores() throws Exception {
    // 租户域门店快照（跨门店调拨与作用域校验的权威来源）；H2 共享库，重复执行已存在即覆盖。
    upsertStore(KTV_STORE, KTV_ORG, "KTV");
    upsertStore(HOTEL_STORE, HOTEL_ORG, "HOTEL");
  }

  @AfterEach
  void clearContext() {
    TenantContextHolder.clear();
  }

  @Test
  void hotelAndKtvInventoryAreIsolatedByBusinessScope() {
    as(KTV_STORE, KTV_ORG, "KTV");
    InventoryMaterialView ktvMaterial = createMaterial("KTV-矿泉水", "水");
    receipt(ktvMaterial.id(), "5", "ktv-receipt-1", null);

    as(HOTEL_STORE, HOTEL_ORG, "HOTEL");
    InventoryMaterialView hotelMaterial = createMaterial("HOTEL-毛巾", "毛巾");
    receipt(hotelMaterial.id(), "3", "hotel-receipt-1", null);

    // 余额作用域：两个门店各自携带自己的 organization/business/warehouse 快照。
    InventoryStock ktvStock = findStock(KTV_STORE, KTV_ORG, "KTV", ktvMaterial.id());
    InventoryStock hotelStock = findStock(HOTEL_STORE, HOTEL_ORG, "HOTEL", hotelMaterial.id());
    assertEquals(0, ktvStock.onHandQty().compareTo(new BigDecimal("5")));
    assertEquals(KTV_ORG, ktvStock.organizationId());
    assertEquals("KTV", ktvStock.businessType());
    assertNotEquals(null, ktvStock.warehouseId(), "余额必须落在具体仓库（V25 作用域）");
    assertEquals(HOTEL_ORG, hotelStock.organizationId());
    assertEquals("HOTEL", hotelStock.businessType());
    assertNotEquals(null, hotelStock.warehouseId());

    // 流水作用域：V24/V25 的三段快照必须与门店一致。
    InventoryTransaction ktvTx = firstTransaction(KTV_STORE, KTV_ORG, "KTV");
    InventoryTransaction hotelTx = firstTransaction(HOTEL_STORE, HOTEL_ORG, "HOTEL");
    assertEquals("KTV", ktvTx.businessType());
    assertEquals(KTV_ORG, ktvTx.organizationId());
    assertEquals(ktvStock.warehouseId(), ktvTx.warehouseId());
    assertEquals("HOTEL", hotelTx.businessType());
    assertEquals(HOTEL_ORG, hotelTx.organizationId());

    // 不串数据：KTV 上下文只看到自己门店的物料/流水，酒店同理。
    as(KTV_STORE, KTV_ORG, "KTV");
    List<InventoryMaterialView> ktvMaterials = inventoryService.listMaterials(1L, 50L, null, null, null, null,
        TimeRangeParams.parse(null, null)).getRecords();
    assertEquals(List.of(ktvMaterial.id()), ktvMaterials.stream().map(InventoryMaterialView::id).toList());
    assertTrue(inventoryService.listTransactions(1L, 50L, null, null, null, null,
        TimeRangeParams.parse(null, null), null).getRecords().stream()
        .allMatch(view -> "KTV".equals(view.businessType())), "KTV 流水列表不得出现酒店流水");

    as(HOTEL_STORE, HOTEL_ORG, "HOTEL");
    List<InventoryMaterialView> hotelMaterials = inventoryService.listMaterials(1L, 50L, null, null, null, null,
        TimeRangeParams.parse(null, null)).getRecords();
    assertEquals(List.of(hotelMaterial.id()), hotelMaterials.stream().map(InventoryMaterialView::id).toList());
    assertTrue(inventoryService.listTransactions(1L, 50L, null, null, null, null,
        TimeRangeParams.parse(null, null), null).getRecords().stream()
        .allMatch(view -> "HOTEL".equals(view.businessType())), "酒店流水列表不得出现 KTV 流水");
  }

  /** 一店多仓：同门店两个仓库的余额互相独立，门店可点性跨仓汇总，仓库过滤能看到各自流水。 */
  @Test
  void warehousesWithinOneStoreAreIsolatedButAvailabilityIsStoreWide() {
    as(MULTI_WAREHOUSE_STORE, KTV_ORG, "KTV");
    InventoryMaterialView material = createMaterial("KTV-一次性拖鞋", "一次性用品");
    InventoryWarehouse second = warehouseRepository.insert(new InventoryWarehouse(null, TENANT_ID, KTV_ORG, "KTV",
        MULTI_WAREHOUSE_STORE, "FLOOR-2", "二楼库房", InventoryWarehouse.STATUS_ACTIVE, 0));

    receipt(material.id(), "5", "ktv-wh-default", null);
    receipt(material.id(), "3", "ktv-wh-second", second.id());

    Long defaultWarehouseId = warehouseRepository.findDefault(TENANT_ID, MULTI_WAREHOUSE_STORE, "KTV")
        .orElseThrow().id();
    InventoryScope defaultScope = scopeOf(MULTI_WAREHOUSE_STORE, KTV_ORG, "KTV", defaultWarehouseId);
    assertEquals(0, stockRepository.find(defaultScope, material.id()).orElseThrow().onHandQty()
        .compareTo(new BigDecimal("5")));
    InventoryScope secondScope = scopeOf(MULTI_WAREHOUSE_STORE, KTV_ORG, "KTV", second.id());
    assertEquals(0, stockRepository.find(secondScope, material.id()).orElseThrow().onHandQty()
        .compareTo(new BigDecimal("3")));

    // 可点性：门店内跨仓汇总（5 + 3），展示口径不按单仓。
    assertEquals(0, inventoryService.availableQuantity(TENANT_ID, MULTI_WAREHOUSE_STORE, material.id())
        .compareTo(new BigDecimal("8")));

    // 流水按仓库过滤：默认仓 1 条、二号仓 1 条，不过滤时门店 2 条。
    assertEquals(1L, inventoryService.listTransactions(1L, 50L, material.id(), null, null,
        defaultWarehouseId, TimeRangeParams.parse(null, null), null).getTotal());
    assertEquals(1L, inventoryService.listTransactions(1L, 50L, material.id(), null, null,
        second.id(), TimeRangeParams.parse(null, null), null).getTotal());
    assertEquals(2L, inventoryService.listTransactions(1L, 50L, material.id(), null, null, null,
        TimeRangeParams.parse(null, null), null).getTotal());
  }

  /** 请求体声明的 organization/businessType 只是待校验声明：与签名门店上下文不一致必须 422。 */
  @Test
  void requestScopeClaimsMustMatchTheSignedStoreContext() {
    as(CLAIM_STORE, KTV_ORG, "KTV");
    BusinessException wrongOrganization = assertThrows(BusinessException.class, () ->
        inventoryService.createMaterial(new InventoryApplicationService.MaterialCommand(CLAIM_STORE,
            "KTV-CLAIM-1", "越权组织", "耗材", "件", BigDecimal.ZERO, null, null, null, null,
            999L, null)));
    assertEquals("ORGANIZATION_MISMATCH", wrongOrganization.getCode());

    BusinessException wrongBusiness = assertThrows(BusinessException.class, () ->
        inventoryService.createMaterial(new InventoryApplicationService.MaterialCommand(CLAIM_STORE,
            "KTV-CLAIM-2", "越权业态", "耗材", "件", BigDecimal.ZERO, null, null, null, null,
            KTV_ORG, "HOTEL")));
    assertEquals("BUSINESS_TYPE_MISMATCH", wrongBusiness.getCode());

    // 库存变更同理：显式声明别的业态不能把库存记到本门店。
    InventoryMaterialView material = createMaterial("KTV-可乐", "饮品");
    BusinessException stockMismatch = assertThrows(BusinessException.class, () ->
        inventoryService.changeStock(new InventoryApplicationService.MovementCommand(material.id(), BigDecimal.ONE,
            "RECEIPT", "RECEIPT", null, "越权业态入库", "claim-stock-1", null, null,
            new InventoryApplicationService.ScopeClaim(KTV_ORG, "HOTEL"))));
    assertEquals("BUSINESS_TYPE_MISMATCH", stockMismatch.getCode());
    assertTrue(stockRepository.findByStore(scopeOf(CLAIM_STORE, KTV_ORG, "KTV", null)).isEmpty(),
        "被拒绝的写入不能留下余额行");
  }

  /** 幂等键是租户级唯一：用 KTV 门店的键到酒店门店重放必须拒绝，不能把库存记到另一个业态。 */
  @Test
  void idempotencyKeyCannotBeReplayedAcrossStoreScopes() {
    as(REPLAY_KTV_STORE, KTV_ORG, "KTV");
    InventoryMaterialView material = createMaterial("KTV-花生", "小食");
    receipt(material.id(), "2", "scope-replay-key", null);

    as(REPLAY_HOTEL_STORE, HOTEL_ORG, "HOTEL");
    InventoryMaterialView hotelMaterial = createMaterial("HOTEL-牙刷", "一次性用品");
    BusinessException replay = assertThrows(BusinessException.class, () ->
        inventoryService.changeStock(new InventoryApplicationService.MovementCommand(hotelMaterial.id(),
            BigDecimal.ONE, "RECEIPT", "RECEIPT", null, "跨门店重放", "scope-replay-key", null, null,
            InventoryApplicationService.ScopeClaim.none())));
    assertEquals("INVENTORY_SCOPE_MISMATCH", replay.getCode());
    assertTrue(stockRepository.findByStore(scopeOf(REPLAY_HOTEL_STORE, HOTEL_ORG, "HOTEL", null)).isEmpty(),
        "跨作用域重放不能留下目标门店余额");
  }

  // ------------------------------------------------------------------ 辅助

  private void as(long storeId, long organizationId, String businessType) {
    TenantContextHolder.set(new TenantContext(TENANT_ID, organizationId, storeId, 0L, 0, List.of(), null,
        businessType, null));
  }

  private InventoryMaterialView createMaterial(String name, String category) {
    return inventoryService.createMaterial(new InventoryApplicationService.MaterialCommand(
        TenantContextHolder.get().storeId(), "INV-" + System.nanoTime(), name, category, "件", BigDecimal.ZERO,
        null, null, null, null));
  }

  private InventoryTransactionView receipt(Long materialId, String quantity, String key, Long warehouseId) {
    return inventoryService.changeStock(new InventoryApplicationService.MovementCommand(materialId,
        new BigDecimal(quantity), "RECEIPT", "RECEIPT", null, "集成测试入库", key, null, warehouseId,
        InventoryApplicationService.ScopeClaim.none()));
  }

  private InventoryStock findStock(long storeId, long organizationId, String businessType, Long materialId) {
    return stockRepository.findByStore(scopeOf(storeId, organizationId, businessType, null)).stream()
        .filter(stock -> materialId.equals(stock.materialId()))
        .findFirst().orElseThrow(() -> new AssertionError("库存行不存在: material=" + materialId));
  }

  private InventoryTransaction firstTransaction(long storeId, long organizationId, String businessType) {
    return transactionRepository.findPage(TransactionQuery.inScope(
            scopeOf(storeId, organizationId, businessType, null), null, null, null, null, null, null, 0L, 50L))
        .transactions().stream().findFirst().orElseThrow(() -> new AssertionError("流水不存在"));
  }

  private static InventoryScope scopeOf(long storeId, long organizationId, String businessType, Long warehouseId) {
    return new InventoryScope(TENANT_ID, organizationId, businessType, storeId, warehouseId);
  }

  private void upsertStore(long storeId, long organizationId, String businessType) throws Exception {
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("MERGE INTO tnt_store (id, tenant_id, organization_id, business_type, status) "
          + "KEY (id) VALUES (" + storeId + ", " + TENANT_ID + ", " + organizationId + ", '" + businessType
          + "', 'ACTIVE')");
    }
  }
}

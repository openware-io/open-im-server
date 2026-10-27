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
 * 跨门店 / 跨业态调拨集成测试（真跑 H2 + Flyway 基线 + MyBatis，仅 mock MQ/审计）：
 *
 * <ul>
 *   <li>源仓原子扣减、目标仓原子增加，**两边各写一条流水**（TRANSFER_OUT / TRANSFER_IN，同一调拨单号关联）；</li>
 *   <li>目标门店的组织/业态来自门店权威快照，请求声明不一致 → 422；</li>
 *   <li>成本随调拨结转：目标仓按源仓移动加权平均成本入账；</li>
 *   <li>库存不足 → 整单拒绝（目标仓不动、不写流水）；重复调拨单号幂等回放。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class InventoryTransferIntegrationTest {

  private static final long TENANT_ID = 3002L;
  private static final long KTV_ORG = 21L;
  private static final long KTV_STORE = 6201L;
  private static final long HOTEL_ORG = 22L;
  private static final long HOTEL_STORE = 6202L;
  private static final long EMPTY_HOTEL_STORE = 6203L;
  private static final String MATERIAL_CODE = "TRANSFER-WATER";

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

  private InventoryMaterialView ktvMaterial;
  private InventoryMaterialView hotelMaterial;

  @BeforeEach
  void setUp() throws Exception {
    // H2 内存库跨测试类复用：先清掉本租户的库存数据，保证每个用例从同一基线开始。
    cleanInventoryData();
    upsertStore(KTV_STORE, KTV_ORG, "KTV");
    upsertStore(HOTEL_STORE, HOTEL_ORG, "HOTEL");
    upsertStore(EMPTY_HOTEL_STORE, HOTEL_ORG, "HOTEL");
    as(KTV_STORE, KTV_ORG, "KTV");
    ktvMaterial = inventoryService.createMaterial(new InventoryApplicationService.MaterialCommand(KTV_STORE,
        MATERIAL_CODE, "调拨矿泉水", "饮品", "瓶", BigDecimal.ZERO, new BigDecimal("300"), null, null, null));
    // 源仓 10 瓶，成本 300/瓶；目标门店建同编码物料（调拨按编码映射）。
    inventoryService.changeStock(new InventoryApplicationService.MovementCommand(ktvMaterial.id(),
        new BigDecimal("10"), "RECEIPT", "RECEIPT", null, "调拨前入库", "transfer-seed-" + ktvMaterial.id(), new BigDecimal("300"),
        null, InventoryApplicationService.ScopeClaim.none()));
    as(HOTEL_STORE, HOTEL_ORG, "HOTEL");
    hotelMaterial = inventoryService.createMaterial(new InventoryApplicationService.MaterialCommand(HOTEL_STORE,
        MATERIAL_CODE, "调拨矿泉水", "饮品", "瓶", BigDecimal.ZERO, null, null, null, null));
    as(EMPTY_HOTEL_STORE, HOTEL_ORG, "HOTEL");
    as(KTV_STORE, KTV_ORG, "KTV");
  }

  @AfterEach
  void clearContext() {
    TenantContextHolder.clear();
  }

  @Test
  void transferWritesTwoScopedTransactionsAndMovesStockAndCost() {
    InventoryApplicationService.TransferResult result = transfer("4", "TR-1", HOTEL_STORE, null, null);

    assertEquals(InventoryTransaction.TYPE_TRANSFER_OUT, result.outbound().transactionType());
    assertEquals(InventoryTransaction.TYPE_TRANSFER_IN, result.inbound().transactionType());
    assertEquals("KTV", result.outbound().businessType());
    assertEquals(KTV_STORE, result.outbound().storeId());
    assertEquals(KTV_ORG, result.outbound().organizationId());
    assertEquals("HOTEL", result.inbound().businessType());
    assertEquals(HOTEL_STORE, result.inbound().storeId());
    assertEquals(HOTEL_ORG, result.inbound().organizationId());
    // 两边流水用同一个 sourceId（调拨单号）关联，且各自落在自己的仓库。
    assertEquals("TR-1", result.outbound().sourceId());
    assertEquals("TR-1", result.inbound().sourceId());
    assertEquals(InventoryTransaction.SOURCE_TRANSFER, result.outbound().sourceType());
    assertEquals(0, result.outbound().quantityDelta().compareTo(new BigDecimal("-4")));
    assertEquals(0, result.inbound().quantityDelta().compareTo(new BigDecimal("4")));
    assertEquals(0, result.outbound().unitCost().compareTo(new BigDecimal("300")), "调拨出库按源仓平均成本结转");
    assertEquals(0, result.inbound().unitCost().compareTo(new BigDecimal("300")), "目标仓按同一单价入账");

    assertEquals(0, onHand(KTV_STORE, KTV_ORG, "KTV", ktvMaterial.id()).compareTo(new BigDecimal("6")));
    Long hotelMaterialId = findHotelMaterialId();
    assertEquals(0, onHand(HOTEL_STORE, HOTEL_ORG, "HOTEL", hotelMaterialId).compareTo(new BigDecimal("4")));
    InventoryStock hotelStock = stockRepository.find(
        warehouseScope(HOTEL_STORE, HOTEL_ORG, "HOTEL", hotelMaterialId), hotelMaterialId).orElseThrow();
    assertEquals(0, hotelStock.avgCostOrZero().compareTo(new BigDecimal("300")), "目标仓移动加权成本随调拨结转");
  }

  @Test
  void replayingTheSameTransferOrderIsIdempotent() {
    InventoryApplicationService.TransferResult first = transfer("4", "TR-IDEMPOTENT", HOTEL_STORE, null, null);
    InventoryApplicationService.TransferResult replay = transfer("4", "TR-IDEMPOTENT", HOTEL_STORE, null, null);

    assertEquals(first.outbound().id(), replay.outbound().id(), "重放返回首次出库流水");
    assertEquals(first.inbound().id(), replay.inbound().id(), "重放返回首次入库流水");
    assertEquals(0, onHand(KTV_STORE, KTV_ORG, "KTV", ktvMaterial.id()).compareTo(new BigDecimal("6")),
        "重放不能重复扣减源仓");
    assertEquals(0, onHand(HOTEL_STORE, HOTEL_ORG, "HOTEL", findHotelMaterialId()).compareTo(new BigDecimal("4")),
        "重放不能重复增加目标仓");
  }

  @Test
  void insufficientSourceStockRejectsWholeTransferWithoutTouchingTargetStore() {
    BusinessException error = assertThrows(BusinessException.class, () -> transfer("100", "TR-INSUFFICIENT",
        HOTEL_STORE, null, null));

    assertEquals("INVENTORY_INSUFFICIENT", error.getCode());
    assertEquals(0, onHand(KTV_STORE, KTV_ORG, "KTV", ktvMaterial.id()).compareTo(new BigDecimal("10")));
    assertTrue(stockRepository.findByStore(scopeOf(HOTEL_STORE, HOTEL_ORG, "HOTEL")).isEmpty(),
        "被拒绝的调拨不能给目标门店留下余额行");
    Long tenantTransactionCount = transactionRepository.findPage(TransactionQuery.inScope(
        scopeOf(HOTEL_STORE, HOTEL_ORG, "HOTEL"), null, null, null, null, null, null, 0L, 100L)).total();
    assertEquals(0L, tenantTransactionCount, "被拒绝的调拨不能写目标门店流水");
  }

  @Test
  void targetStoreScopeClaimsMustMatchTheStoreAuthoritativeSnapshot() {
    BusinessException wrongOrganization = assertThrows(BusinessException.class, () -> transfer("1", "TR-WRONG-ORG",
        HOTEL_STORE, null, 999L));
    assertEquals("ORGANIZATION_MISMATCH", wrongOrganization.getCode());

    BusinessException wrongBusiness = assertThrows(BusinessException.class, () ->
        inventoryService.transfer(new InventoryApplicationService.TransferCommand(ktvMaterial.id(),
            BigDecimal.ONE, HOTEL_STORE, null, null, null, "业态错配", "TR-WRONG-BIZ", null, null,
            null, "KTV")));
    assertEquals("BUSINESS_TYPE_MISMATCH", wrongBusiness.getCode());
  }

  @Test
  void targetStoreWithoutMatchingMaterialIsRejected() {
    BusinessException error = assertThrows(BusinessException.class, () -> transfer("1", "TR-NO-MATERIAL",
        EMPTY_HOTEL_STORE, null, null));
    assertEquals("TRANSFER_MATERIAL_NOT_FOUND", error.getCode());
    assertEquals(0, onHand(KTV_STORE, KTV_ORG, "KTV", ktvMaterial.id()).compareTo(new BigDecimal("10")),
        "目标门店没有物料时源仓不能被扣减");
  }

  @Test
  void transferRequiresDifferentSourceAndTargetStores() {
    BusinessException error = assertThrows(BusinessException.class, () ->
        inventoryService.transfer(new InventoryApplicationService.TransferCommand(ktvMaterial.id(),
            BigDecimal.ONE, KTV_STORE, null, null, null, "同店调拨", "TR-SAME-STORE", null, null, null, null)));
    assertEquals("TRANSFER_SAME_STORE", error.getCode(),
        "同门店但未指定目标仓库 → 无法区分仓库间移库与原仓原地调拨，必须拒绝");
  }

  /** 同门店仓库间移库：两边流水同属该门店业态，但仓库不同（V25 作用域必须有仓库维度）。 */
  @Test
  void intraStoreTransferMovesStockBetweenWarehouses() {
    InventoryWarehouse secondWarehouse = warehouseRepository.insert(new InventoryWarehouse(null, TENANT_ID, KTV_ORG,
        "KTV", KTV_STORE, "FLOOR-2", "二楼库房", InventoryWarehouse.STATUS_ACTIVE, 0));

    InventoryApplicationService.TransferResult result = inventoryService.transfer(
        new InventoryApplicationService.TransferCommand(ktvMaterial.id(), new BigDecimal("3"), KTV_STORE, null,
            null, secondWarehouse.id(), "仓库间移库", "TR-WAREHOUSE-MOVE", null, null, null, null));

    assertEquals("KTV", result.outbound().businessType());
    assertEquals("KTV", result.inbound().businessType());
    assertEquals(InventoryTransaction.TYPE_TRANSFER_OUT, result.outbound().transactionType());
    assertEquals(InventoryTransaction.TYPE_TRANSFER_IN, result.inbound().transactionType());
    assertEquals(0, result.outbound().quantityDelta().compareTo(new BigDecimal("-3")));
    assertEquals(0, result.inbound().quantityDelta().compareTo(new BigDecimal("3")));
    assertNotEquals(result.outbound().warehouseId(), result.inbound().warehouseId(),
        "仓库间移库的两边流水必须落在不同仓库");
    // 默认仓剩 7 件、二号仓 3 件，门店内合计仍为 10 件（移库不改总量）。
    assertEquals(0, onHand(KTV_STORE, KTV_ORG, "KTV", ktvMaterial.id()).compareTo(new BigDecimal("10")));
    Long defaultWarehouseId = warehouseRepository.findDefault(TENANT_ID, KTV_STORE, "KTV").orElseThrow().id();
    InventoryScope defaultScope = new InventoryScope(TENANT_ID, KTV_ORG, "KTV", KTV_STORE, defaultWarehouseId);
    InventoryScope secondScope = new InventoryScope(TENANT_ID, KTV_ORG, "KTV", KTV_STORE, secondWarehouse.id());
    assertEquals(0, stockRepository.find(defaultScope, ktvMaterial.id()).orElseThrow().onHandQty()
        .compareTo(new BigDecimal("7")));
    assertEquals(0, stockRepository.find(secondScope, ktvMaterial.id()).orElseThrow().onHandQty()
        .compareTo(new BigDecimal("3")));
  }

  // ------------------------------------------------------------------ 辅助

  private InventoryApplicationService.TransferResult transfer(String quantity, String key, long toStoreId,
                                                              Long fromWarehouseId, Long toOrganizationId) {
    return inventoryService.transfer(new InventoryApplicationService.TransferCommand(ktvMaterial.id(),
        new BigDecimal(quantity), toStoreId, null, fromWarehouseId, null, "集成测试调拨", key, null, null,
        toOrganizationId, null));
  }

  private Long findHotelMaterialId() {
    return hotelMaterial.id();
  }

  private BigDecimal onHand(long storeId, long organizationId, String businessType, Long materialId) {
    return stockRepository.findByStore(scopeOf(storeId, organizationId, businessType)).stream()
        .filter(stock -> materialId.equals(stock.materialId()))
        .map(InventoryStock::onHandOrZero).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private InventoryScope warehouseScope(long storeId, long organizationId, String businessType, Long materialId) {
    Long warehouseId = warehouseRepository.findDefault(TENANT_ID, storeId, businessType).orElseThrow().id();
    return new InventoryScope(TENANT_ID, organizationId, businessType, storeId, warehouseId);
  }

  private static InventoryScope scopeOf(long storeId, long organizationId, String businessType) {
    return InventoryScope.store(TENANT_ID, organizationId, businessType, storeId);
  }

  private void as(long storeId, long organizationId, String businessType) {
    TenantContextHolder.set(new TenantContext(TENANT_ID, organizationId, storeId, 0L, 0, List.of(), null,
        businessType, null));
  }

  private void cleanInventoryData() throws Exception {
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("DELETE FROM ord_inventory_transaction WHERE tenant_id = " + TENANT_ID);
      statement.executeUpdate("DELETE FROM ord_inventory_stock WHERE tenant_id = " + TENANT_ID);
      statement.executeUpdate("DELETE FROM ord_inventory_warehouse WHERE tenant_id = " + TENANT_ID);
      statement.executeUpdate("DELETE FROM ord_inventory_material WHERE tenant_id = " + TENANT_ID);
    }
  }
  private void upsertStore(long storeId, long organizationId, String businessType) throws Exception {
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("MERGE INTO tnt_store (id, tenant_id, organization_id, business_type, status) "
          + "KEY (id) VALUES (" + storeId + ", " + TENANT_ID + ", " + organizationId + ", '" + businessType
          + "', 'ACTIVE')");
    }
  }
}

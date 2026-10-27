package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import io.openware.platform.order.domain.inventory.repository.InventoryStockRepository;
import io.openware.platform.order.domain.inventory.repository.InventoryTransactionRepository;
import io.openware.platform.order.domain.inventory.repository.InventoryWarehouseRepository;
import io.openware.platform.order.infra.mq.EventOutboxRelay;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 盘点与消费接入集成测试（真跑 H2 + Flyway 基线 + MyBatis，仅 mock MQ/审计）。
 *
 * <ul>
 *   <li><b>盘点</b>：账面 vs 实盘，差异落 ADJUST_IN / ADJUST_OUT 流水（来源 STOCKTAKE），
 *       不直接改数量、不允许负库存、无差异不写流水、幂等键可重放；</li>
 *   <li><b>消费接入</b>：客房领用 / 迷你吧销售 / KTV 商品出库 / 订单加项共用同一扣减入口，
 *       只写 sourceType/sourceId，<b>不写房间号</b>（库存不绑定房间）；库存不足拒绝且不留流水。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class InventoryStocktakeAndConsumptionIntegrationTest {

  private static final long TENANT_ID = 3003L;
  private static final long ORGANIZATION_ID = 31L;
  private static final long STORE_ID = 6301L;
  private static final String MATERIAL_CODE = "STOCKTAKE-TOWEL";

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

  private InventoryMaterialView material;

  @BeforeEach
  void setUp() {
    asStore();
    material = inventoryService.createMaterial(new InventoryApplicationService.MaterialCommand(STORE_ID,
        MATERIAL_CODE, "盘点毛巾", "毛巾", "条", BigDecimal.ZERO, new BigDecimal("200"), null, null, null));
    inventoryService.changeStock(new InventoryApplicationService.MovementCommand(material.id(), new BigDecimal("10"),
        "RECEIPT", "RECEIPT", null, "盘点前入库", "stocktake-seed-" + System.nanoTime(), new BigDecimal("300"),
        null, InventoryApplicationService.ScopeClaim.none()));
  }

  @AfterEach
  void clearContext() {
    TenantContextHolder.clear();
  }

  @Test
  void stocktakeShortageWritesAdjustOutTransaction() {
    InventoryApplicationService.StocktakeResult result = inventoryService.stocktake(
        new InventoryApplicationService.StocktakeCommand(material.id(), new BigDecimal("7"), null,
            "月底盘点", "stocktake-shortage", null, null));

    assertTrue(result.adjusted());
    assertEquals(0, result.onHandBefore().compareTo(new BigDecimal("10")));
    assertEquals(0, result.countedQuantity().compareTo(new BigDecimal("7")));
    assertEquals(0, result.delta().compareTo(new BigDecimal("-3")));
    assertEquals(InventoryTransaction.TYPE_ADJUST_OUT, result.transaction().transactionType());
    assertEquals(InventoryTransaction.SOURCE_STOCKTAKE, result.transaction().sourceType());
    assertEquals(0, result.transaction().quantityDelta().compareTo(new BigDecimal("-3")));
    assertEquals(0, result.transaction().unitCost().compareTo(new BigDecimal("300")), "盘亏按当前平均成本结转");
    assertEquals(0, onHand().compareTo(new BigDecimal("7")));
  }

  @Test
  void stocktakeSurplusWritesAdjustInTransactionAndKeepsCostBasis() {
    InventoryApplicationService.StocktakeResult result = inventoryService.stocktake(
        new InventoryApplicationService.StocktakeCommand(material.id(), new BigDecimal("12"), null,
            "月底盘盈", "stocktake-surplus", null, null));

    assertTrue(result.adjusted());
    assertEquals(0, result.delta().compareTo(new BigDecimal("2")));
    assertEquals(InventoryTransaction.TYPE_ADJUST_IN, result.transaction().transactionType());
    assertEquals(0, onHand().compareTo(new BigDecimal("12")));
    assertEquals(0, stock().avgCostOrZero().compareTo(new BigDecimal("300")),
        "盘盈按当前平均成本入账，不改变成本基准");
  }

  @Test
  void stocktakeWithoutVarianceDoesNotWriteTransaction() {
    long before = transactionCount();
    InventoryApplicationService.StocktakeResult result = inventoryService.stocktake(
        new InventoryApplicationService.StocktakeCommand(material.id(), new BigDecimal("10"), null,
            "账实一致", "stocktake-equal", null, null));

    assertFalse(result.adjusted());
    assertEquals(0, result.delta().signum());
    assertNull(result.transaction(), "无差异不写流水");
    assertEquals(before, transactionCount(), "无差异不得新增流水");
  }

  @Test
  void stocktakeRejectsNegativeCountAndReplayIsIdempotent() {
    BusinessException negative = assertThrows(BusinessException.class, () -> inventoryService.stocktake(
        new InventoryApplicationService.StocktakeCommand(material.id(), new BigDecimal("-1"), null,
            "非法实盘", "stocktake-negative", null, null)));
    assertEquals("INVENTORY_INVALID", negative.getCode());

    InventoryApplicationService.StocktakeResult first = inventoryService.stocktake(
        new InventoryApplicationService.StocktakeCommand(material.id(), new BigDecimal("8"), null,
            "盘点", "stocktake-replay", null, null));
    long transactionsAfterFirst = transactionCount();
    InventoryApplicationService.StocktakeResult replay = inventoryService.stocktake(
        new InventoryApplicationService.StocktakeCommand(material.id(), new BigDecimal("8"), null,
            "盘点重放", "stocktake-replay", null, null));

    assertEquals(first.transaction().id(), replay.transaction().id(), "重放返回首次调整流水");
    assertEquals(transactionsAfterFirst, transactionCount(), "重放不能重复写流水");
    assertEquals(0, onHand().compareTo(new BigDecimal("8")), "重放不能重复调整库存");
  }

  @Test
  void consumptionScenesWriteSourceTypeAndNeverBindRooms() {
    InventoryTransactionView roomUse = consume("1", "ROOM_CONSUMPTION", "folio-1", "room-consume");
    assertEquals(InventoryTransaction.TYPE_CONSUME, roomUse.transactionType());
    assertEquals(InventoryTransaction.SOURCE_ROOM_CONSUMPTION, roomUse.sourceType());
    assertEquals("folio-1", roomUse.sourceId());
    assertEquals("KTV", roomUse.businessType());
    assertEquals(STORE_ID, roomUse.storeId());

    InventoryTransactionView minibar = consume("2", "MINIBAR_SALE", "bill-2", "minibar-consume");
    assertEquals(InventoryTransaction.SOURCE_MINIBAR_SALE, minibar.sourceType());

    InventoryTransactionView ktvItem = consume("3", "KTV_ITEM", "order-item-3", "ktv-consume");
    assertEquals(InventoryTransaction.SOURCE_KTV_ITEM, ktvItem.sourceType());

    InventoryTransactionView orderItem = consume("1", "ORDER_ITEM", "order-item-4", "order-consume");
    assertEquals(InventoryTransaction.SOURCE_ORDER_ITEM, orderItem.sourceType());

    assertEquals(0, onHand().compareTo(new BigDecimal("3")), "10 - 1 - 2 - 3 - 1 = 3");
    // 流水里没有房间/包厢维度：库存是通用域，不绑定房间（sourceId 只是业务单号）。
    assertTrue(transactionRepository.findPage(InventoryTransactionRepository.TransactionQuery.inScope(
        scope(), material.id(), "CONSUME", null, null, null, null, 0L, 100L)).transactions().stream()
        .allMatch(tx -> tx.sourceType() != null && tx.sourceId() != null));
  }

  @Test
  void consumptionRejectsUnknownSceneAndInsufficientStock() {
    BusinessException invalidScene = assertThrows(BusinessException.class, () -> consume("1", "ROOM_SERVICE", null,
        "bad-scene"));
    assertEquals("INVENTORY_SOURCE_INVALID", invalidScene.getCode());

    long before = transactionCount();
    BusinessException insufficient = assertThrows(BusinessException.class,
        () -> consume("999", "ROOM_CONSUMPTION", "folio-9", "too-many"));
    assertEquals("INVENTORY_INSUFFICIENT", insufficient.getCode());
    assertEquals(0, onHand().compareTo(new BigDecimal("10")), "扣减失败不改库存");
    assertEquals(before, transactionCount(), "扣减失败不留流水");
  }

  // ------------------------------------------------------------------ 辅助

  private InventoryTransactionView consume(String quantity, String scene, String sourceId, String key) {
    return inventoryService.consume(new InventoryApplicationService.ConsumeCommand(material.id(),
        new BigDecimal(quantity), scene, sourceId, "集成测试消费", key, null, null, null));
  }

  private BigDecimal onHand() {
    return stock().onHandOrZero();
  }

  private InventoryStock stock() {
    return stockRepository.find(scope(), material.id()).orElseThrow();
  }

  private long transactionCount() {
    return transactionRepository.findPage(InventoryTransactionRepository.TransactionQuery.inScope(
        scope(), null, null, null, null, null, null, 0L, 100L)).total();
  }

  private InventoryScope scope() {
    Long warehouseId = warehouseRepository.findDefault(TENANT_ID, STORE_ID, "KTV").orElseThrow().id();
    return new InventoryScope(TENANT_ID, ORGANIZATION_ID, "KTV", STORE_ID, warehouseId);
  }

  private void asStore() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, ORGANIZATION_ID, STORE_ID, 0L, 0, List.of(), null,
        "KTV", null));
  }
}

package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.MqProducer;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.controller.InventoryRecoveryAdminController;
import io.openware.platform.order.infra.mq.EventOutboxRelay;
import io.openware.platform.order.infra.persistence.mapper.CatalogItemMapper;
import io.openware.platform.order.infra.persistence.mapper.InventoryStockMapper;
import io.openware.platform.order.infra.persistence.mapper.InventoryTransactionMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderItemMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.po.CatalogItemPo;
import io.openware.platform.order.infra.persistence.po.InventoryMaterialPo;
import io.openware.platform.order.infra.persistence.po.InventoryStockPo;
import io.openware.platform.order.infra.persistence.po.InventoryTransactionPo;
import io.openware.platform.order.infra.persistence.po.OrderItemPo;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import io.openware.platform.order.infra.persistence.po.ProductPo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 库存防超卖集成测试（真跑 H2 + Flyway + MyBatis，仅 mock MQ/审计）：
 * 1) 库存不足必须拒绝（INVENTORY_INSUFFICIENT）且不改动库存、不写流水；
 * 2) 并发扣减不超卖：8 个线程抢 5 件，恰好 5 单成功、库存归零不为负；
 * 3) 作废回补链路（admin 逐项确认）能加回库存且幂等；
 * 4) 商品上架/改「实物商品」开关会把目录项 stock_controlled 缓存列一起回写（F3）。
 */
@SpringBootTest
@ActiveProfiles("test")
class InventoryStockIntegrationTest {

  private static final long TENANT_ID = 1001L;
  private static final long STORE_ID = 2501L;

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
  private ProductApplicationService productService;

  @Autowired
  private InventoryStockMapper stockMapper;

  @Autowired
  private InventoryTransactionMapper transactionMapper;

  @Autowired
  private CatalogItemMapper catalogItemMapper;

  @Autowired
  private OrderMapper orderMapper;

  @Autowired
  private OrderItemMapper orderItemMapper;

  @Autowired
  private InventoryRecoveryAdminController recoveryController;

  @BeforeEach
  void setUpTenant() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 0L, 0));
  }

  @AfterEach
  void tearDownTenant() {
    TenantContextHolder.clear();
  }

  @Test
  void consumeRejectsWhenAvailableStockIsInsufficientAndKeepsStockUntouched() {
    InventoryMaterialPo material = newMaterial("不足拒绝");
    inventoryService.changeStock(material.getId(), new BigDecimal("2"), "RECEIPT", "集成测试入库", "ins-" + material.getId());

    BusinessException error = assertThrows(BusinessException.class, () -> inventoryService.changeStock(
        material.getId(), new BigDecimal("3"), "CONSUME", "ORDER_ITEM", "1", "超量扣减", "consume-fail-" + material.getId()));

    assertEquals("INVENTORY_INSUFFICIENT", error.getCode());
    assertEquals(0, onHand(material.getId()).compareTo(new BigDecimal("2")));
    assertTrue(consumedTransactions(material.getId()).isEmpty(), "扣减失败不能留下流水");
  }

  /** 条件更新语义：可用量不足时受影响 0 行，库存一动不动（不依赖应用层「先读后判」）。 */
  @Test
  void conditionalDeductAffectsZeroRowsWhenNotEnough() {
    InventoryMaterialPo material = newMaterial("条件更新");
    inventoryService.changeStock(material.getId(), new BigDecimal("2"), "RECEIPT", "集成测试入库", "cond-" + material.getId());

    int affected = stockMapper.deductAvailable(TENANT_ID, STORE_ID, material.getId(), new BigDecimal("3"), LocalDateTime.now());
    assertEquals(0, affected);
    assertEquals(0, onHand(material.getId()).compareTo(new BigDecimal("2")));

    int ok = stockMapper.deductAvailable(TENANT_ID, STORE_ID, material.getId(), new BigDecimal("2"), LocalDateTime.now());
    assertEquals(1, ok);
    assertEquals(0, onHand(material.getId()).signum(), "扣到 0 可以，绝不允许为负");
  }

  /** 并发扣减不超卖：8 线程抢 5 件 → 5 成功 3 失败（全部 INVENTORY_INSUFFICIENT），库存归零不为负。 */
  @Test
  void concurrentConsumeNeverOversells() throws Exception {
    InventoryMaterialPo material = newMaterial("并发扣减");
    inventoryService.changeStock(material.getId(), new BigDecimal("5"), "RECEIPT", "集成测试入库", "conc-" + material.getId());

    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger success = new AtomicInteger();
    AtomicInteger insufficient = new AtomicInteger();
    AtomicInteger unexpected = new AtomicInteger();
    for (int i = 0; i < threads; i++) {
      final int index = i;
      pool.submit(() -> {
        try {
          TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 0L, 0));
          ready.countDown();
          start.await(30, TimeUnit.SECONDS);
          inventoryService.changeStock(material.getId(), BigDecimal.ONE, "CONSUME", "ORDER_ITEM",
              String.valueOf(index), "并发抢购", "conc-consume-" + material.getId() + "-" + index);
          success.incrementAndGet();
        } catch (BusinessException e) {
          if ("INVENTORY_INSUFFICIENT".equals(e.getCode())) {
            insufficient.incrementAndGet();
          } else {
            unexpected.incrementAndGet();
          }
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
    assertTrue(done.await(60, TimeUnit.SECONDS), "并发扣减未在超时时间内结束");
    pool.shutdownNow();

    assertEquals(5, success.get(), "库存 5 件必须恰好成功 5 单");
    assertEquals(3, insufficient.get(), "其余请求必须被明确拒绝（库存不足）");
    assertEquals(0, unexpected.get(), "不应出现库存不足以外的异常");
    assertEquals(0, onHand(material.getId()).signum());
    assertEquals(5, consumedTransactions(material.getId()).size());
  }

  /** 作废回补：CONSUMED 明细经后台确认后加回库存，且同一明细不可重复回补。 */
  @Test
  void voidedOrderRecoveryReturnsStockAndIsIdempotent() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 0L, 0, List.of(
        "inventory.recovery.view", "inventory.recovery.confirm")));
    InventoryMaterialPo material = newMaterial("作废回补");
    inventoryService.changeStock(material.getId(), new BigDecimal("5"), "RECEIPT", "集成测试入库", "rev-" + material.getId());

    OrderPo order = newOrder("VOIDED");
    OrderItemPo item = new OrderItemPo();
    item.setTenantId(TENANT_ID);
    item.setOrderId(order.getId());
    item.setItemType("PRODUCT");
    item.setNameSnapshot("回补商品");
    item.setUnitPrice(new BigDecimal("1000"));
    item.setQuantity(new BigDecimal("2"));
    item.setDiscountAmount(BigDecimal.ZERO);
    item.setTaxAmount(BigDecimal.ZERO);
    item.setTotalAmount(new BigDecimal("2000"));
    item.setStatus("ACTIVE");
    item.setSource("MERCHANT");
    item.setInventoryStatus("NOT_APPLICABLE");
    item.setCreatedAt(LocalDateTime.now());
    item.setUpdatedAt(LocalDateTime.now());
    orderItemMapper.insert(item);
    // 模拟加项扣减 2 件（幂等键与 Controller 回补键同一明细维度）
    inventoryService.changeStock(material.getId(), new BigDecimal("2"), "CONSUME", "ORDER_ITEM",
        String.valueOf(item.getId()), "订单加项扣减", "order-item:" + item.getId() + ":consume");
    item.setInventoryStatus("CONSUMED");
    item.setInventoryMaterialId(material.getId());
    orderItemMapper.updateById(item);
    assertEquals(0, onHand(material.getId()).compareTo(new BigDecimal("3")));

    List<OrderItemPo> pending = recoveryController.list(order.getId());
    assertEquals(1, pending.size(), "作废订单的已扣减明细必须出现在回补列表");

    OrderItemPo recovered = recoveryController.decide(order.getId(), item.getId(),
        new InventoryRecoveryAdminController.RecoveryRequest(true, "作废回补"));
    assertEquals("REVERSED", recovered.getInventoryStatus());
    assertEquals("RECOVERED", recovered.getInventoryRecoveryDecision());
    assertEquals(0, onHand(material.getId()).compareTo(new BigDecimal("5")), "回补后库存必须回到 5");

    BusinessException repeated = assertThrows(BusinessException.class, () -> recoveryController.decide(
        order.getId(), item.getId(), new InventoryRecoveryAdminController.RecoveryRequest(true, "重复回补")));
    assertEquals("INVENTORY_RECOVERY_INVALID", repeated.getCode());
    assertEquals(0, onHand(material.getId()).compareTo(new BigDecimal("5")), "重复回补不能重复加库存");
  }

  /** 不回补：明细标记 NOT_RECOVERED，库存不变。 */
  @Test
  void recoveryDecisionCanRefuseWithoutTouchingStock() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 0L, 0, List.of(
        "inventory.recovery.view", "inventory.recovery.confirm")));
    InventoryMaterialPo material = newMaterial("拒绝回补");
    OrderPo order = newOrder("VOIDED");
    OrderItemPo item = new OrderItemPo();
    item.setTenantId(TENANT_ID);
    item.setOrderId(order.getId());
    item.setItemType("PRODUCT");
    item.setNameSnapshot("不回补商品");
    item.setUnitPrice(new BigDecimal("1000"));
    item.setQuantity(BigDecimal.ONE);
    item.setDiscountAmount(BigDecimal.ZERO);
    item.setTaxAmount(BigDecimal.ZERO);
    item.setTotalAmount(new BigDecimal("1000"));
    item.setStatus("ACTIVE");
    item.setInventoryStatus("CONSUMED");
    item.setInventoryMaterialId(material.getId());
    item.setCreatedAt(LocalDateTime.now());
    item.setUpdatedAt(LocalDateTime.now());
    orderItemMapper.insert(item);

    OrderItemPo decided = recoveryController.decide(order.getId(), item.getId(),
        new InventoryRecoveryAdminController.RecoveryRequest(false, "顾客已取走"));

    assertEquals("NOT_RECOVERED", decided.getInventoryStatus());
    assertEquals("NOT_RECOVERED", decided.getInventoryRecoveryDecision());
    assertEquals(0, onHand(material.getId()).signum());
  }

  /** F3：syncCatalog 必须把商品的「是否扣库存」回写到目录项缓存列，否则加项就漏扣库存。 */
  @Test
  void syncCatalogWritesStockControlledCacheColumn() {
    InventoryMaterialPo material = newMaterial("同步列");
    ProductPo product = productService.create(new ProductApplicationService.ProductCommand(
        STORE_ID, "SYNC-P-" + System.nanoTime(), "实物商品", "饮品", "瓶", new BigDecimal("1000"),
        material.getId(), true, 0, null, null, null));

    assertNotNull(product.getCatalogItemId());
    CatalogItemPo catalog = catalogItemMapper.selectById(product.getCatalogItemId());
    assertEquals(Boolean.TRUE, catalog.getStockControlled(), "实物商品的目录项必须标记为受库存控制");

    // 关掉「实物商品」开关后，目录项缓存列必须跟着变 false
    productService.update(product.getId(), new ProductApplicationService.ProductCommand(
        null, null, null, null, null, null, null, false, null, null, null, null));
    assertEquals(Boolean.FALSE, catalogItemMapper.selectById(product.getCatalogItemId()).getStockControlled());
  }

  /** 点单列表的批量库存读：一次取回多个物料，未建库存行的按 0（售罄），并写入 TTL 缓存。 */
  @Test
  void batchAvailabilityReturnsPerMaterialQuantities() {
    InventoryMaterialPo inStock = newMaterial("批量有货");
    InventoryMaterialPo neverReceived = newMaterial("批量无货");
    inventoryService.changeStock(inStock.getId(), new BigDecimal("3"), "RECEIPT", "集成测试入库", "batch-" + inStock.getId());

    java.util.Map<Long, BigDecimal> availability = inventoryService.availableQuantities(
        TENANT_ID, STORE_ID, List.of(inStock.getId(), neverReceived.getId()));

    assertEquals(0, availability.get(inStock.getId()).compareTo(new BigDecimal("3")));
    assertEquals(0, availability.get(neverReceived.getId()).signum(), "没有库存行的物料按售罄（0）处理");
    // 再读一次命中缓存，结果一致（缓存不影响正确性）
    assertEquals(0, inventoryService.availableQuantities(TENANT_ID, STORE_ID, List.of(inStock.getId()))
        .get(inStock.getId()).compareTo(new BigDecimal("3")));
  }

  private InventoryMaterialPo newMaterial(String name) {
    return inventoryService.createMaterial(new InventoryApplicationService.MaterialCommand(
        STORE_ID, "INV-" + System.nanoTime(), name, "耗材", "件", BigDecimal.ZERO, null, null, null, null));
  }

  private OrderPo newOrder(String status) {
    OrderPo po = new OrderPo();
    po.setTenantId(TENANT_ID);
    po.setOrganizationId(1L);
    po.setStoreId(STORE_ID);
    po.setOrderNo("ORD-INV-" + System.nanoTime());
    po.setBusinessType("KTV");
    po.setStatus(status);
    po.setCurrencyCode("CNY");
    po.setSubtotalAmount(BigDecimal.ZERO);
    po.setDiscountAmount(BigDecimal.ZERO);
    po.setTaxAmount(BigDecimal.ZERO);
    po.setTotalAmount(BigDecimal.ZERO);
    po.setPaidAmount(BigDecimal.ZERO);
    po.setRefundableAmount(BigDecimal.ZERO);
    po.setVersion(0);
    po.setCreatedAt(LocalDateTime.now());
    po.setUpdatedAt(LocalDateTime.now());
    orderMapper.insert(po);
    return po;
  }

  private BigDecimal onHand(Long materialId) {
    InventoryStockPo stock = stockMapper.selectOne(new LambdaQueryWrapper<InventoryStockPo>()
        .eq(InventoryStockPo::getTenantId, TENANT_ID)
        .eq(InventoryStockPo::getStoreId, STORE_ID)
        .eq(InventoryStockPo::getMaterialId, materialId));
    return stock == null ? BigDecimal.ZERO : stock.getOnHandQty();
  }

  private List<InventoryTransactionPo> consumedTransactions(Long materialId) {
    return transactionMapper.selectList(new QueryWrapper<InventoryTransactionPo>()
        .eq("tenant_id", TENANT_ID).eq("store_id", STORE_ID)
        .eq("material_id", materialId).eq("transaction_type", "CONSUME"));
  }
}

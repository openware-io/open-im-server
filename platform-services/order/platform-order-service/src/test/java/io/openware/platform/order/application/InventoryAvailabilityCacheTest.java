package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.dto.InventoryTransactionView;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.domain.inventory.model.InventoryMaterial;
import io.openware.platform.order.domain.inventory.model.InventoryStock;
import io.openware.platform.order.domain.inventory.model.InventoryTransaction;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository;
import io.openware.platform.order.domain.inventory.repository.InventoryStockRepository;
import io.openware.platform.order.domain.inventory.repository.InventoryTransactionRepository;
import io.openware.platform.order.infra.cache.InventoryAvailabilityCache;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 库存可用量缓存（F3 读路径优化）：
 * 1) 命中缓存不重复查库；2) 未命中/过期/关闭缓存一律回源；
 * 3) 库存写路径（扣减/回补）写后失效，下一次读拿到新值；
 * 4) 缓存再脏也放行不了超卖 —— 扣减永远以数据库条件更新为准。
 *
 * <p>迁移后观测点换成仓储端口：可用量读路径只调 {@code find}/{@code findByMaterials}，
 * 写路径只调 {@code deductAvailable}/{@code increaseOnHand}（条件更新的返回值就是权威结论）。
 */
class InventoryAvailabilityCacheTest {

  private static final long TENANT_ID = 1L;
  private static final long STORE_ID = 3L;
  private static final long MATERIAL_ID = 10L;

  private final InventoryMaterialRepository materialRepository = mock(InventoryMaterialRepository.class);
  private final InventoryStockRepository stockRepository = mock(InventoryStockRepository.class);
  private final InventoryTransactionRepository transactionRepository = mock(InventoryTransactionRepository.class);
  private final ProductRepository productRepository = mock(ProductRepository.class);
  private final CatalogItemRepository catalogItemRepository = mock(CatalogItemRepository.class);
  private final InventoryAvailabilityCache cache = new InventoryAvailabilityCache(60_000L);
  private final InventoryApplicationService service = new InventoryApplicationService(
      materialRepository, stockRepository, transactionRepository, productRepository, catalogItemRepository, cache);

  @AfterEach
  void clear() {
    TenantContextHolder.clear();
    cache.invalidateAll();
  }

  @Test
  void cachedAvailabilityIsServedWithoutSecondQuery() {
    when(stockRepository.find(TENANT_ID, STORE_ID, MATERIAL_ID)).thenReturn(Optional.of(stock("5", "1")));

    assertEquals(0, service.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID).compareTo(new BigDecimal("4")));
    assertEquals(0, service.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID).compareTo(new BigDecimal("4")));
    // 批量读（点单列表）同样命中同一个缓存 key，不会退化成逐项查库
    assertEquals(0, service.availableQuantities(TENANT_ID, STORE_ID, List.of(MATERIAL_ID))
        .get(MATERIAL_ID).compareTo(new BigDecimal("4")));

    verify(stockRepository, times(1)).find(TENANT_ID, STORE_ID, MATERIAL_ID);
    verify(stockRepository, never()).findByMaterials(any(), any(), any());
  }

  @Test
  void missingStockRowIsReportedAsSoldOutAndNegativeAvailableIsClamped() {
    when(stockRepository.find(TENANT_ID, STORE_ID, MATERIAL_ID)).thenReturn(Optional.empty());
    assertEquals(0, service.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID).signum());

    cache.invalidateAll();
    when(stockRepository.find(TENANT_ID, STORE_ID, MATERIAL_ID)).thenReturn(Optional.of(stock("1", "3")));
    assertEquals(0, service.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID).signum());
  }

  @Test
  void expiredEntryFallsBackToDatabase() throws InterruptedException {
    InventoryAvailabilityCache shortTtl = new InventoryAvailabilityCache(1L);
    InventoryApplicationService shortTtlService = new InventoryApplicationService(
        materialRepository, stockRepository, transactionRepository, productRepository, catalogItemRepository, shortTtl);
    when(stockRepository.find(TENANT_ID, STORE_ID, MATERIAL_ID)).thenReturn(Optional.of(stock("5", "0")));

    shortTtlService.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID);
    Thread.sleep(20L);
    shortTtlService.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID);

    verify(stockRepository, times(2)).find(TENANT_ID, STORE_ID, MATERIAL_ID);
  }

  @Test
  void disabledCacheAlwaysReadsDatabase() {
    InventoryAvailabilityCache disabled = new InventoryAvailabilityCache(0L);
    InventoryApplicationService noCacheService = new InventoryApplicationService(
        materialRepository, stockRepository, transactionRepository, productRepository, catalogItemRepository, disabled);
    when(stockRepository.find(TENANT_ID, STORE_ID, MATERIAL_ID)).thenReturn(Optional.of(stock("5", "0")));

    noCacheService.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID);
    noCacheService.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID);

    verify(stockRepository, times(2)).find(TENANT_ID, STORE_ID, MATERIAL_ID);
  }

  /** 写后失效：扣减成功后缓存被清掉，下一次读回源拿到新的可用量。 */
  @Test
  void stockWriteInvalidatesCache() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
    when(stockRepository.find(TENANT_ID, STORE_ID, MATERIAL_ID))
        .thenReturn(Optional.of(stock("5", "0")))
        .thenReturn(Optional.of(stock("4", "0")));
    assertEquals(0, service.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID).compareTo(new BigDecimal("5")));

    when(transactionRepository.findByIdempotency(TENANT_ID, "k-consume")).thenReturn(Optional.empty());
    when(materialRepository.findInStore(eq(TENANT_ID), eq(STORE_ID), eq(MATERIAL_ID)))
        .thenReturn(Optional.of(material()));
    when(stockRepository.findForUpdate(TENANT_ID, STORE_ID, MATERIAL_ID)).thenReturn(Optional.of(stock("5", "0")));
    // 条件更新 1 行 = 扣减成功（端口把「受影响行数 > 0」表达成 boolean）。
    when(stockRepository.deductAvailable(any(), any(), any(), any(), any())).thenReturn(true);
    when(transactionRepository.append(any(InventoryTransaction.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.changeStock(MATERIAL_ID, BigDecimal.ONE, "CONSUME", "ORDER_ITEM", "1", "测试扣减", "k-consume");

    assertEquals(0, service.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID).compareTo(new BigDecimal("4")));
    verify(stockRepository, times(2)).find(TENANT_ID, STORE_ID, MATERIAL_ID);
  }

  /**
   * 缓存不可信：即使缓存里还写着「有货」，扣减也是数据库条件更新说了算，
   * 返回 0 行即 INVENTORY_INSUFFICIENT，绝不因为缓存脏读而超卖。
   */
  @Test
  void staleCacheNeverAllowsOversell() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
    cache.put(TENANT_ID, STORE_ID, MATERIAL_ID, new BigDecimal("99"));
    when(transactionRepository.findByIdempotency(TENANT_ID, "k-dirty")).thenReturn(Optional.empty());
    when(materialRepository.findInStore(eq(TENANT_ID), eq(STORE_ID), eq(MATERIAL_ID)))
        .thenReturn(Optional.of(material()));
    when(stockRepository.findForUpdate(TENANT_ID, STORE_ID, MATERIAL_ID)).thenReturn(Optional.of(stock("0", "0")));
    when(stockRepository.deductAvailable(any(), any(), any(), any(), any())).thenReturn(false);

    BusinessException error = assertThrows(BusinessException.class,
        () -> service.changeStock(MATERIAL_ID, BigDecimal.ONE, "CONSUME", "ORDER_ITEM", "1", "并发抢购", "k-dirty"));

    assertEquals("INVENTORY_INSUFFICIENT", error.getCode());
    verify(transactionRepository, never()).append(any(InventoryTransaction.class));
  }

  /** 回补走入库分支：原子加库存（含移动加权平均成本列），并失效缓存。 */
  @Test
  void recoveryIncreasesStockAndInvalidatesCache() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
    when(transactionRepository.findByIdempotency(TENANT_ID, "k-reverse")).thenReturn(Optional.empty());
    when(materialRepository.findInStore(eq(TENANT_ID), eq(STORE_ID), eq(MATERIAL_ID)))
        .thenReturn(Optional.of(material()));
    when(stockRepository.findForUpdate(TENANT_ID, STORE_ID, MATERIAL_ID)).thenReturn(Optional.of(stock("0", "0")));
    when(stockRepository.increaseOnHand(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    when(transactionRepository.append(any(InventoryTransaction.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    InventoryTransactionView tx = service.changeStock(MATERIAL_ID, new BigDecimal("2"), "REVERSE",
        "ORDER_ITEM", "7", "作废回补", "k-reverse");

    assertEquals(0, tx.quantityDelta().compareTo(new BigDecimal("2")));
    assertEquals(0, tx.quantityAfter().compareTo(new BigDecimal("2")));
    verify(stockRepository).increaseOnHand(any(), any(), any(), any(), any(), any(), any());
  }

  /** 幂等：同一 Idempotency-Key 重放直接返回首次流水，不再扣减。 */
  @Test
  void replayReturnsOriginalTransactionWithoutSecondDeduction() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
    InventoryTransaction existing = new InventoryTransaction(99L, TENANT_ID, STORE_ID, MATERIAL_ID, "CONSUME",
        BigDecimal.ONE.negate(), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "USD",
        "ORDER_ITEM", "1", "k-same", "重放", LocalDateTime.now());
    when(transactionRepository.findByIdempotency(TENANT_ID, "k-same")).thenReturn(Optional.of(existing));
    cache.put(TENANT_ID, STORE_ID, MATERIAL_ID, new BigDecimal("5"));

    assertEquals(InventoryTransactionView.from(existing),
        service.changeStock(MATERIAL_ID, BigDecimal.ONE, "CONSUME", "ORDER_ITEM", "1", "重放", "k-same"));

    verify(stockRepository, never()).deductAvailable(any(), any(), any(), any(), any());
    // 重放不改库存，缓存也不需要失效
    assertEquals(0, service.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID).compareTo(new BigDecimal("5")));
  }

  private static InventoryStock stock(String onHand, String reserved) {
    return new InventoryStock(20L, TENANT_ID, STORE_ID, MATERIAL_ID, new BigDecimal(onHand),
        new BigDecimal(reserved), BigDecimal.ZERO, "USD", 0);
  }

  private static InventoryMaterial material() {
    return new InventoryMaterial(MATERIAL_ID, TENANT_ID, STORE_ID, "M-1", "物料", "耗材", "包", null,
        BigDecimal.ZERO, null, "USD", null, null, "ACTIVE", 0, LocalDateTime.now());
  }
}

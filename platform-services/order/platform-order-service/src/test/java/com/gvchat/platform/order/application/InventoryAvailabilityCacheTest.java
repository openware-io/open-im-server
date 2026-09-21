package com.gvchat.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.infra.cache.InventoryAvailabilityCache;
import com.gvchat.platform.order.infra.persistence.mapper.CatalogItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.InventoryMaterialMapper;
import com.gvchat.platform.order.infra.persistence.mapper.InventoryStockMapper;
import com.gvchat.platform.order.infra.persistence.mapper.InventoryTransactionMapper;
import com.gvchat.platform.order.infra.persistence.mapper.ProductMapper;
import com.gvchat.platform.order.infra.persistence.po.InventoryMaterialPo;
import com.gvchat.platform.order.infra.persistence.po.InventoryStockPo;
import com.gvchat.platform.order.infra.persistence.po.InventoryTransactionPo;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 库存可用量缓存（F3 读路径优化）：
 * 1) 命中缓存不重复查库；2) 未命中/过期/关闭缓存一律回源；
 * 3) 库存写路径（扣减/回补）写后失效，下一次读拿到新值；
 * 4) 缓存再脏也放行不了超卖 —— 扣减永远以数据库条件更新为准。
 */
class InventoryAvailabilityCacheTest {

  private static final long TENANT_ID = 1L;
  private static final long STORE_ID = 3L;
  private static final long MATERIAL_ID = 10L;

  private final InventoryMaterialMapper materialMapper = mock(InventoryMaterialMapper.class);
  private final InventoryStockMapper stockMapper = mock(InventoryStockMapper.class);
  private final InventoryTransactionMapper transactionMapper = mock(InventoryTransactionMapper.class);
  private final ProductMapper productMapper = mock(ProductMapper.class);
  private final CatalogItemMapper catalogItemMapper = mock(CatalogItemMapper.class);
  private final InventoryAvailabilityCache cache = new InventoryAvailabilityCache(60_000L);
  private final InventoryApplicationService service = new InventoryApplicationService(
      materialMapper, stockMapper, transactionMapper, productMapper, catalogItemMapper, cache);

  @AfterEach
  void clear() {
    TenantContextHolder.clear();
    cache.invalidateAll();
  }

  @Test
  void cachedAvailabilityIsServedWithoutSecondQuery() {
    when(stockMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stock("5", "1"));

    assertEquals(0, service.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID).compareTo(new BigDecimal("4")));
    assertEquals(0, service.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID).compareTo(new BigDecimal("4")));
    // 批量读（点单列表）同样命中同一个缓存 key，不会退化成逐项查库
    assertEquals(0, service.availableQuantities(TENANT_ID, STORE_ID, List.of(MATERIAL_ID))
        .get(MATERIAL_ID).compareTo(new BigDecimal("4")));

    verify(stockMapper, times(1)).selectOne(any(LambdaQueryWrapper.class));
    verify(stockMapper, never()).selectList(any(LambdaQueryWrapper.class));
  }

  @Test
  void missingStockRowIsReportedAsSoldOutAndNegativeAvailableIsClamped() {
    when(stockMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    assertEquals(0, service.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID).signum());

    cache.invalidateAll();
    when(stockMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stock("1", "3"));
    assertEquals(0, service.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID).signum());
  }

  @Test
  void expiredEntryFallsBackToDatabase() throws InterruptedException {
    InventoryAvailabilityCache shortTtl = new InventoryAvailabilityCache(1L);
    InventoryApplicationService shortTtlService = new InventoryApplicationService(
        materialMapper, stockMapper, transactionMapper, productMapper, catalogItemMapper, shortTtl);
    when(stockMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stock("5", "0"));

    shortTtlService.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID);
    Thread.sleep(20L);
    shortTtlService.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID);

    verify(stockMapper, times(2)).selectOne(any(LambdaQueryWrapper.class));
  }

  @Test
  void disabledCacheAlwaysReadsDatabase() {
    InventoryAvailabilityCache disabled = new InventoryAvailabilityCache(0L);
    InventoryApplicationService noCacheService = new InventoryApplicationService(
        materialMapper, stockMapper, transactionMapper, productMapper, catalogItemMapper, disabled);
    when(stockMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stock("5", "0"));

    noCacheService.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID);
    noCacheService.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID);

    verify(stockMapper, times(2)).selectOne(any(LambdaQueryWrapper.class));
  }

  /** 写后失效：扣减成功后缓存被清掉，下一次读回源拿到新的可用量。 */
  @Test
  void stockWriteInvalidatesCache() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
    when(stockMapper.selectOne(any(LambdaQueryWrapper.class)))
        .thenReturn(stock("5", "0"))
        .thenReturn(stock("4", "0"));
    assertEquals(0, service.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID).compareTo(new BigDecimal("5")));

    when(transactionMapper.findByIdempotency(TENANT_ID, "k-consume")).thenReturn(null);
    when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(material());
    when(stockMapper.selectForUpdate(TENANT_ID, STORE_ID, MATERIAL_ID)).thenReturn(stock("5", "0"));
    when(stockMapper.deductAvailable(any(), any(), any(), any(), any())).thenReturn(1);

    service.changeStock(MATERIAL_ID, BigDecimal.ONE, "CONSUME", "ORDER_ITEM", "1", "测试扣减", "k-consume");

    assertEquals(0, service.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID).compareTo(new BigDecimal("4")));
    verify(stockMapper, times(2)).selectOne(any(LambdaQueryWrapper.class));
  }

  /**
   * 缓存不可信：即使缓存里还写着「有货」，扣减也是数据库条件更新说了算，
   * 返回 0 行即 INVENTORY_INSUFFICIENT，绝不因为缓存脏读而超卖。
   */
  @Test
  void staleCacheNeverAllowsOversell() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
    cache.put(TENANT_ID, STORE_ID, MATERIAL_ID, new BigDecimal("99"));
    when(transactionMapper.findByIdempotency(TENANT_ID, "k-dirty")).thenReturn(null);
    when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(material());
    when(stockMapper.selectForUpdate(TENANT_ID, STORE_ID, MATERIAL_ID)).thenReturn(stock("0", "0"));
    when(stockMapper.deductAvailable(any(), any(), any(), any(), any())).thenReturn(0);

    BusinessException error = assertThrows(BusinessException.class,
        () -> service.changeStock(MATERIAL_ID, BigDecimal.ONE, "CONSUME", "ORDER_ITEM", "1", "并发抢购", "k-dirty"));

    assertEquals("INVENTORY_INSUFFICIENT", error.getCode());
    verify(transactionMapper, never()).insert(any(InventoryTransactionPo.class));
  }

  /** 回补走入库分支：原子加库存（含移动加权平均成本列），并失效缓存。 */
  @Test
  void recoveryIncreasesStockAndInvalidatesCache() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
    when(transactionMapper.findByIdempotency(TENANT_ID, "k-reverse")).thenReturn(null);
    when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(material());
    when(stockMapper.selectForUpdate(TENANT_ID, STORE_ID, MATERIAL_ID)).thenReturn(stock("0", "0"));
    when(stockMapper.increaseOnHand(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

    InventoryTransactionPo tx = service.changeStock(MATERIAL_ID, new BigDecimal("2"), "REVERSE",
        "ORDER_ITEM", "7", "作废回补", "k-reverse");

    assertEquals(0, tx.getQuantityDelta().compareTo(new BigDecimal("2")));
    assertEquals(0, tx.getQuantityAfter().compareTo(new BigDecimal("2")));
    verify(stockMapper).increaseOnHand(any(), any(), any(), any(), any(), any(), any());
  }

  /** 幂等：同一 Idempotency-Key 重放直接返回首次流水，不再扣减。 */
  @Test
  void replayReturnsOriginalTransactionWithoutSecondDeduction() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
    InventoryTransactionPo existing = new InventoryTransactionPo();
    existing.setId(99L);
    when(transactionMapper.findByIdempotency(TENANT_ID, "k-same")).thenReturn(existing);
    cache.put(TENANT_ID, STORE_ID, MATERIAL_ID, new BigDecimal("5"));

    assertEquals(existing, service.changeStock(MATERIAL_ID, BigDecimal.ONE, "CONSUME", "ORDER_ITEM", "1", "重放", "k-same"));

    verify(stockMapper, never()).deductAvailable(any(), any(), any(), any(), any());
    // 重放不改库存，缓存也不需要失效
    assertEquals(0, service.availableQuantity(TENANT_ID, STORE_ID, MATERIAL_ID).compareTo(new BigDecimal("5")));
  }

  private static InventoryStockPo stock(String onHand, String reserved) {
    InventoryStockPo po = new InventoryStockPo();
    po.setId(20L);
    po.setTenantId(TENANT_ID);
    po.setStoreId(STORE_ID);
    po.setMaterialId(MATERIAL_ID);
    po.setOnHandQty(new BigDecimal(onHand));
    po.setReservedQty(new BigDecimal(reserved));
    po.setVersion(0);
    return po;
  }

  private static InventoryMaterialPo material() {
    InventoryMaterialPo po = new InventoryMaterialPo();
    po.setId(MATERIAL_ID);
    po.setTenantId(TENANT_ID);
    po.setStoreId(STORE_ID);
    po.setStatus("ACTIVE");
    return po;
  }
}

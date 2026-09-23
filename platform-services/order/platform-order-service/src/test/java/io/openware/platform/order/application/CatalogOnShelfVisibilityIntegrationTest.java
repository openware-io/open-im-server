package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.MqProducer;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.controller.CatalogController;
import io.openware.platform.order.infra.mq.EventOutboxRelay;
import io.openware.platform.order.infra.persistence.mapper.CatalogItemMapper;
import io.openware.platform.order.infra.persistence.po.CatalogItemPo;
import io.openware.platform.order.infra.persistence.po.InventoryMaterialPo;
import io.openware.platform.order.infra.persistence.po.ProductPo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 下单列表（GET /business/catalog/items）可见性集成测试（真跑 H2 + Flyway + MyBatis）：
 * 1) 商品上架 → 出现在下单列表；下架 → 消失；
 * 2) 没关联商品的目录项（历史种子数据）不出现；
 * 3) 售罄的商品仍然展示，只是 available=false + 原因（不能直接隐藏）。
 * 用独立 STORE_ID 与其它集成测试隔离，避免共享 H2 里的历史数据串味。
 */
@SpringBootTest
@ActiveProfiles("test")
class CatalogOnShelfVisibilityIntegrationTest {

  private static final long TENANT_ID = 1001L;
  private static final long STORE_ID = 2401L;

  @MockitoBean
  private MqProducer mqProducer;

  @MockitoBean
  private MqConsumerFactory mqConsumerFactory;

  @MockitoBean
  private AuditClient auditClient;

  @MockitoBean
  private EventOutboxRelay eventOutboxRelay;

  @Autowired
  private ProductApplicationService productService;

  @Autowired
  private InventoryApplicationService inventoryService;

  @Autowired
  private CatalogController catalogController;

  @Autowired
  private CatalogItemMapper catalogItemMapper;

  @BeforeEach
  void setUpTenant() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 0L, 0));
  }

  @AfterEach
  void tearDownTenant() {
    TenantContextHolder.clear();
  }

  @Test
  void productAppearsAfterOnShelfAndDisappearsAfterOffShelf() {
    ProductPo product = productService.create(new ProductApplicationService.ProductCommand(
        STORE_ID, "VIS-P-" + System.nanoTime(), "上架可见商品", "饮品", "瓶", new BigDecimal("1200"),
        null, false, 0, "描述", null, null));
    Long catalogItemId = product.getCatalogItemId();
    assertNotNull(catalogItemId, "商品创建后应同步出目录项");
    assertEquals("INACTIVE", catalogItemMapper.selectById(catalogItemId).getStatus(), "草稿商品目录项必须 INACTIVE");
    assertFalse(visibleCatalogItemIds().contains(catalogItemId), "草稿商品不能出现在下单列表");

    productService.onShelf(product.getId());
    assertEquals("ACTIVE", catalogItemMapper.selectById(catalogItemId).getStatus(), "上架后目录项必须 ACTIVE");
    assertTrue(visibleCatalogItemIds().contains(catalogItemId), "上架后必须出现在下单列表");

    productService.offShelf(product.getId());
    assertFalse(visibleCatalogItemIds().contains(catalogItemId), "下架后必须从下单列表消失");
  }

  /** 本地库 1–10 号种子目录项都没有 product_id，改造后不再出现在下单列表。 */
  @Test
  void catalogItemWithoutLinkedProductIsHidden() {
    CatalogItemPo seed = new CatalogItemPo();
    seed.setTenantId(TENANT_ID);
    seed.setStoreId(STORE_ID);
    seed.setProductId(null);
    seed.setCategory("酒水");
    seed.setItemType("PRODUCT");
    seed.setName("种子目录项-" + System.nanoTime());
    seed.setUnit("瓶");
    seed.setUnitPrice(new BigDecimal("1500"));
    seed.setStatus("ACTIVE");
    seed.setStockControlled(false);
    seed.setSortOrder(1);
    seed.setCreatedAt(LocalDateTime.now());
    seed.setUpdatedAt(LocalDateTime.now());
    catalogItemMapper.insert(seed);

    assertFalse(visibleCatalogItemIds().contains(seed.getId()), "没关联商品的目录项不能出现在下单列表");
  }

  /** 售罄（可用库存 0）不能消失：仍在下单列表里，只是 available=false + 「已售罄」。 */
  @Test
  void soldOutStockControlledProductStaysVisibleButUnavailable() {
    long suffix = System.nanoTime();
    InventoryMaterialPo material = inventoryService.createMaterial(new InventoryApplicationService.MaterialCommand(
        STORE_ID, "VIS-M-" + suffix, "可见性物料", "耗材", "包", BigDecimal.ZERO, null, "物料描述", null, null));
    ProductPo product = productService.create(new ProductApplicationService.ProductCommand(
        STORE_ID, "VIS-P2-" + suffix, "售罄商品", "饮品", "瓶", new BigDecimal("1000"),
        material.getId(), true, 0, null, null, null));
    productService.onShelf(product.getId());
    Long catalogItemId = product.getCatalogItemId();

    CatalogItemPo soldOut = visibleCatalogItem(catalogItemId);
    assertNotNull(soldOut, "售罄商品必须仍在下单列表（前端置灰展示，而不是消失）");
    assertFalse(soldOut.getAvailable());
    assertEquals("已售罄", soldOut.getUnavailableReason());

    inventoryService.changeStock(material.getId(), new BigDecimal("5"), "RECEIPT", "集成测试入库", "vis-" + suffix);

    CatalogItemPo inStock = visibleCatalogItem(catalogItemId);
    assertNotNull(inStock);
    assertTrue(inStock.getAvailable(), "有货后必须恢复可点");
  }

  /**
   * F3 读取侧兜底：目录项 stock_controlled 只是查询缓存，权威口径是「商品 + 物料」。
   * 即便缓存列是 0（V17 回填之前的历史数据），实物商品库存 0 也必须判「已售罄」不可点。
   */
  @Test
  void staleCatalogStockFlagIsIgnoredBecauseProductIsAuthoritative() {
    long suffix = System.nanoTime();
    InventoryMaterialPo material = inventoryService.createMaterial(new InventoryApplicationService.MaterialCommand(
        STORE_ID, "VIS-M2-" + suffix, "缓存脏数据物料", "耗材", "包", BigDecimal.ZERO, null, null, null, null));
    ProductPo product = productService.create(new ProductApplicationService.ProductCommand(
        STORE_ID, "VIS-P3-" + suffix, "缓存脏数据商品", "饮品", "瓶", new BigDecimal("1000"),
        material.getId(), true, 0, null, null, null));
    productService.onShelf(product.getId());
    Long catalogItemId = product.getCatalogItemId();

    CatalogItemPo stale = catalogItemMapper.selectById(catalogItemId);
    stale.setStockControlled(false);
    catalogItemMapper.updateById(stale);

    CatalogItemPo soldOut = visibleCatalogItem(catalogItemId);
    assertNotNull(soldOut);
    assertFalse(soldOut.getAvailable(), "商品是实物且库存 0，目录项缓存列是 0 也必须判售罄");
    assertEquals("已售罄", soldOut.getUnavailableReason());
    assertTrue(soldOut.getStockControlled(), "响应里必须回写商品的权威库存控制标记");

    inventoryService.changeStock(material.getId(), new BigDecimal("3"), "RECEIPT", "集成测试入库", "stale-" + suffix);

    assertTrue(visibleCatalogItem(catalogItemId).getAvailable(), "回补库存后必须恢复可点");
  }

  private Set<Long> visibleCatalogItemIds() {
    return visibleItems().stream().map(CatalogItemPo::getId).collect(Collectors.toSet());
  }

  private CatalogItemPo visibleCatalogItem(Long catalogItemId) {
    return visibleItems().stream().filter(item -> item.getId().equals(catalogItemId)).findFirst().orElse(null);
  }

  private List<CatalogItemPo> visibleItems() {
    return catalogController.list(null, STORE_ID);
  }
}

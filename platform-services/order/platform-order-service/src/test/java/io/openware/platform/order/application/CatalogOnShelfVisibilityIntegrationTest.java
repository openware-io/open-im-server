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
import io.openware.platform.order.api.dto.CatalogItemView;
import io.openware.platform.order.api.dto.ProductView;
import io.openware.platform.order.domain.catalog.model.CatalogItem;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.infra.mq.EventOutboxRelay;
import io.openware.platform.order.infra.persistence.po.InventoryMaterialPo;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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
 *
 * <p><b>迁移后的读口径</b>：目录项落库经 {@link CatalogItemRepository}（领域端口，不可变 record），
 * 列表响应读 {@link CatalogItemView}（JSON 字段名与原 {@code CatalogItemPo} 一致）。
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
  private CatalogItemRepository catalogItemRepository;

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
    ProductView product = productService.create(new ProductApplicationService.ProductCommand(
        STORE_ID, "VIS-P-" + System.nanoTime(), "上架可见商品", "饮品", "瓶", new BigDecimal("1200"),
        null, false, 0, "描述", null, null));
    Long catalogItemId = product.catalogItemId();
    assertNotNull(catalogItemId, "商品创建后应同步出目录项");
    assertEquals("INACTIVE", loadCatalogItem(catalogItemId).status(), "草稿商品目录项必须 INACTIVE");
    assertFalse(visibleCatalogItemIds().contains(catalogItemId), "草稿商品不能出现在下单列表");

    productService.onShelf(product.id());
    assertEquals("ACTIVE", loadCatalogItem(catalogItemId).status(), "上架后目录项必须 ACTIVE");
    assertTrue(visibleCatalogItemIds().contains(catalogItemId), "上架后必须出现在下单列表");

    productService.offShelf(product.id());
    assertFalse(visibleCatalogItemIds().contains(catalogItemId), "下架后必须从下单列表消失");
  }

  /** 本地库 1–10 号种子目录项都没有 product_id，改造后不再出现在下单列表。 */
  @Test
  void catalogItemWithoutLinkedProductIsHidden() {
    CatalogItem seed = catalogItemRepository.insert(new CatalogItem(null, null, TENANT_ID, STORE_ID, "酒水",
        "PRODUCT", "种子目录项-" + System.nanoTime(), "瓶", new BigDecimal("1500"), null, List.of(), null,
        "ACTIVE", false, 1)).aggregate();

    assertFalse(visibleCatalogItemIds().contains(seed.id()), "没关联商品的目录项不能出现在下单列表");
  }

  /** 售罄（可用库存 0）不能消失：仍在下单列表里，只是 available=false + 「已售罄」。 */
  @Test
  void soldOutStockControlledProductStaysVisibleButUnavailable() {
    long suffix = System.nanoTime();
    InventoryMaterialPo material = inventoryService.createMaterial(new InventoryApplicationService.MaterialCommand(
        STORE_ID, "VIS-M-" + suffix, "可见性物料", "耗材", "包", BigDecimal.ZERO, null, "物料描述", null, null));
    ProductView product = productService.create(new ProductApplicationService.ProductCommand(
        STORE_ID, "VIS-P2-" + suffix, "售罄商品", "饮品", "瓶", new BigDecimal("1000"),
        material.getId(), true, 0, null, null, null));
    productService.onShelf(product.id());
    Long catalogItemId = product.catalogItemId();

    CatalogItemView soldOut = visibleCatalogItem(catalogItemId);
    assertNotNull(soldOut, "售罄商品必须仍在下单列表（前端置灰展示，而不是消失）");
    assertFalse(soldOut.available());
    assertEquals("已售罄", soldOut.unavailableReason());

    inventoryService.changeStock(material.getId(), new BigDecimal("5"), "RECEIPT", "集成测试入库", "vis-" + suffix);

    CatalogItemView inStock = visibleCatalogItem(catalogItemId);
    assertNotNull(inStock);
    assertTrue(inStock.available(), "有货后必须恢复可点");
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
    ProductView product = productService.create(new ProductApplicationService.ProductCommand(
        STORE_ID, "VIS-P3-" + suffix, "缓存脏数据商品", "饮品", "瓶", new BigDecimal("1000"),
        material.getId(), true, 0, null, null, null));
    productService.onShelf(product.id());
    Long catalogItemId = product.catalogItemId();

    // 把缓存列改成 0（模拟 V17 回填之前的历史数据）：经端口写一次，领域模型不可变。
    catalogItemRepository.update(loadCatalogItem(catalogItemId)
        .withStockControlled(false));

    CatalogItemView soldOut = visibleCatalogItem(catalogItemId);
    assertNotNull(soldOut);
    assertFalse(soldOut.available(), "商品是实物且库存 0，目录项缓存列是 0 也必须判售罄");
    assertEquals("已售罄", soldOut.unavailableReason());
    assertTrue(soldOut.stockControlled(), "响应里必须回写商品的权威库存控制标记");

    inventoryService.changeStock(material.getId(), new BigDecimal("3"), "RECEIPT", "集成测试入库", "stale-" + suffix);

    assertTrue(visibleCatalogItem(catalogItemId).available(), "回补库存后必须恢复可点");
  }

  private CatalogItem loadCatalogItem(Long id) {
    Optional<CatalogItem> found = catalogItemRepository.findById(id);
    assertNotNull(found.orElse(null), "目录项应存在: " + id);
    return found.orElseThrow();
  }

  private Set<Long> visibleCatalogItemIds() {
    return visibleItems().stream().map(CatalogItemView::id).collect(Collectors.toSet());
  }

  private CatalogItemView visibleCatalogItem(Long catalogItemId) {
    return visibleItems().stream().filter(item -> item.id().equals(catalogItemId)).findFirst().orElse(null);
  }

  private List<CatalogItemView> visibleItems() {
    return catalogController.list(null, STORE_ID);
  }
}

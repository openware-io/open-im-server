package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.MqProducer;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.dto.ProductView;
import io.openware.platform.order.domain.catalog.model.CatalogItem;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.infra.mq.EventOutboxRelay;
import io.openware.platform.order.api.dto.InventoryMaterialView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 点单目录图片同步集成测试（真跑 H2 + Flyway + MyBatis）：
 * 断言「改了商品/物料图片之后，点单目录项（B 端点单、C 端自助加项的数据源）里确实有图」。
 *
 * <p><b>迁移后的读口径</b>：目录项不再从 Mapper 读 PO，而是经
 * {@link CatalogItemRepository}（领域端口）读领域模型；断言的是 record 的访问器。
 * 这条路径同时覆盖适配器的 JSON 列读写（{@code image_urls} 的 JacksonTypeHandler）。
 */
@SpringBootTest
@ActiveProfiles("test")
class CatalogImageSyncIntegrationTest {

  private static final long TENANT_ID = 1001L;
  private static final long STORE_ID = 2001L;

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
  void updatingProductImagesUpdatesCatalogItemImages() {
    ProductView product = productService.create(new ProductApplicationService.ProductCommand(
        STORE_ID, "IMG-SYNC-P-" + System.nanoTime(), "同步商品", "饮品", "瓶", new BigDecimal("1200"),
        null, false, 0, "描述", List.of("/api/v1/media-public/open-media-public/saas/1001/202609/p1.png"), null));
    Long catalogItemId = product.catalogItemId();
    assertNotNull(catalogItemId, "商品创建后应同步出目录项");
    assertEquals(List.of("/api/v1/media-public/open-media-public/saas/1001/202609/p1.png"),
        loadCatalogItem(catalogItemId).imagesOrEmpty(), "目录项应带上商品图");

    productService.update(product.id(), new ProductApplicationService.ProductCommand(
        null, null, null, null, null, null, null, null, null, null,
        List.of("/api/v1/media-public/open-media-public/saas/1001/202609/p2.png",
            "/api/v1/media-public/open-media-public/saas/1001/202609/p3.png"),
        "/api/v1/media-public/open-media-public/saas/1001/202609/p3.png"));

    CatalogItem catalog = loadCatalogItem(catalogItemId);
    assertEquals(List.of("/api/v1/media-public/open-media-public/saas/1001/202609/p2.png",
            "/api/v1/media-public/open-media-public/saas/1001/202609/p3.png"),
        catalog.imagesOrEmpty(), "改商品图片后目录项必须同步新图");
    assertEquals("/api/v1/media-public/open-media-public/saas/1001/202609/p3.png", catalog.mainImageUrl());
  }

  @Test
  void materialImagesSyncToCatalogItemWhenProductHasNoOwnImages() {
    InventoryMaterialView material = inventoryService.createMaterial(new InventoryApplicationService.MaterialCommand(
            STORE_ID, "IMG-SYNC-M-" + System.nanoTime(), "同步物料", "耗材", "包", BigDecimal.ZERO, null,
            "点单用物料描述",
            List.of("/api/v1/media-public/open-media-public/saas/1001/202609/m1.png"), null));
    ProductView product = productService.create(new ProductApplicationService.ProductCommand(
        STORE_ID, "IMG-SYNC-P2-" + System.nanoTime(), "物料图商品", "饮品", "瓶", new BigDecimal("1000"),
        material.id(), false, 0, null, null, null));

    Long catalogItemId = product.catalogItemId();
    assertNotNull(catalogItemId, "商品创建后应同步出目录项");
    assertEquals(List.of("/api/v1/media-public/open-media-public/saas/1001/202609/m1.png"),
        loadCatalogItem(catalogItemId).imagesOrEmpty(), "商品无图时目录项回退到物料图");

    inventoryService.updateMaterial(material.id(), new InventoryApplicationService.MaterialCommand(
        null, null, null, null, null, null, null, null,
        List.of("/api/v1/media-public/open-media-public/saas/1001/202609/m2.png",
            "/api/v1/media-public/open-media-public/saas/1001/202609/m3.png"),
        "/api/v1/media-public/open-media-public/saas/1001/202609/m3.png"));

    CatalogItem catalog = loadCatalogItem(catalogItemId);
    assertEquals(List.of("/api/v1/media-public/open-media-public/saas/1001/202609/m2.png",
            "/api/v1/media-public/open-media-public/saas/1001/202609/m3.png"),
        catalog.imagesOrEmpty(), "改物料图片后目录项必须同步新图");
    assertEquals("/api/v1/media-public/open-media-public/saas/1001/202609/m3.png", catalog.mainImageUrl());
  }

  private CatalogItem loadCatalogItem(Long id) {
    Optional<CatalogItem> found = catalogItemRepository.findById(id);
    assertNotNull(found.orElse(null), "目录项应存在: " + id);
    return found.orElseThrow();
  }
}

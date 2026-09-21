package com.gvchat.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.MqProducer;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.infra.mq.EventOutboxRelay;
import com.gvchat.platform.order.infra.persistence.mapper.CatalogItemMapper;
import com.gvchat.platform.order.infra.persistence.po.CatalogItemPo;
import com.gvchat.platform.order.infra.persistence.po.InventoryMaterialPo;
import com.gvchat.platform.order.infra.persistence.po.ProductPo;
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
 * 点单目录图片同步集成测试（真跑 H2 + Flyway + MyBatis）：
 * 断言「改了商品/物料图片之后，点单目录项（B 端点单、C 端自助加项的数据源）里确实有图」。
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
  void updatingProductImagesUpdatesCatalogItemImages() {
    ProductPo product = productService.create(new ProductApplicationService.ProductCommand(
        STORE_ID, "IMG-SYNC-P-" + System.nanoTime(), "同步商品", "饮品", "瓶", new BigDecimal("1200"),
        null, false, 0, "描述", List.of("/api/v1/media-public/gv-media-public/saas/1001/202609/p1.png"), null));
    Long catalogItemId = product.getCatalogItemId();
    assertNotNull(catalogItemId, "商品创建后应同步出目录项");
    assertEquals(List.of("/api/v1/media-public/gv-media-public/saas/1001/202609/p1.png"),
        catalogItemMapper.selectById(catalogItemId).getImageUrls(), "目录项应带上商品图");

    productService.update(product.getId(), new ProductApplicationService.ProductCommand(
        null, null, null, null, null, null, null, null, null, null,
        List.of("/api/v1/media-public/gv-media-public/saas/1001/202609/p2.png",
            "/api/v1/media-public/gv-media-public/saas/1001/202609/p3.png"),
        "/api/v1/media-public/gv-media-public/saas/1001/202609/p3.png"));

    CatalogItemPo catalog = catalogItemMapper.selectById(catalogItemId);
    assertEquals(List.of("/api/v1/media-public/gv-media-public/saas/1001/202609/p2.png",
            "/api/v1/media-public/gv-media-public/saas/1001/202609/p3.png"),
        catalog.getImageUrls(), "改商品图片后目录项必须同步新图");
    assertEquals("/api/v1/media-public/gv-media-public/saas/1001/202609/p3.png", catalog.getMainImageUrl());
  }

  @Test
  void materialImagesSyncToCatalogItemWhenProductHasNoOwnImages() {
    InventoryMaterialPo material = inventoryService.createMaterial(new InventoryApplicationService.MaterialCommand(
        STORE_ID, "IMG-SYNC-M-" + System.nanoTime(), "同步物料", "耗材", "包", BigDecimal.ZERO, null, "点单用物料描述",
        List.of("/api/v1/media-public/gv-media-public/saas/1001/202609/m1.png"), null));
    ProductPo product = productService.create(new ProductApplicationService.ProductCommand(
        STORE_ID, "IMG-SYNC-P2-" + System.nanoTime(), "物料图商品", "饮品", "瓶", new BigDecimal("1000"),
        material.getId(), false, 0, null, null, null));

    Long catalogItemId = product.getCatalogItemId();
    assertNotNull(catalogItemId, "商品创建后应同步出目录项");
    assertEquals(List.of("/api/v1/media-public/gv-media-public/saas/1001/202609/m1.png"),
        catalogItemMapper.selectById(catalogItemId).getImageUrls(), "商品无图时目录项回退到物料图");

    inventoryService.updateMaterial(material.getId(), new InventoryApplicationService.MaterialCommand(
        null, null, null, null, null, null, null, null,
        List.of("/api/v1/media-public/gv-media-public/saas/1001/202609/m2.png",
            "/api/v1/media-public/gv-media-public/saas/1001/202609/m3.png"),
        "/api/v1/media-public/gv-media-public/saas/1001/202609/m3.png"));

    CatalogItemPo catalog = catalogItemMapper.selectById(catalogItemId);
    assertEquals(List.of("/api/v1/media-public/gv-media-public/saas/1001/202609/m2.png",
            "/api/v1/media-public/gv-media-public/saas/1001/202609/m3.png"),
        catalog.getImageUrls(), "改物料图片后目录项必须同步新图");
    assertEquals("/api/v1/media-public/gv-media-public/saas/1001/202609/m3.png", catalog.getMainImageUrl());
  }
}

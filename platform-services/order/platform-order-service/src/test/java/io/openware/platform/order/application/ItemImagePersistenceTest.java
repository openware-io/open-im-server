package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.MqProducer;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.infra.mq.EventOutboxRelay;
import io.openware.platform.order.infra.persistence.mapper.CatalogItemMapper;
import io.openware.platform.order.infra.persistence.mapper.InventoryMaterialMapper;
import io.openware.platform.order.infra.persistence.mapper.ProductMapper;
import io.openware.platform.order.infra.persistence.po.CatalogItemPo;
import io.openware.platform.order.infra.persistence.po.InventoryMaterialPo;
import io.openware.platform.order.infra.persistence.po.ProductPo;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
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
 * 商品/物料多图落库集成测试（真跑 H2 + Flyway + MyBatis）：
 * 验证 V13 新增的 image_urls（JSON 数组）/ main_image_url 两列可写入并原样读回。
 */
@SpringBootTest
@ActiveProfiles("test")
class ItemImagePersistenceTest {

  private static final long TENANT_ID = 1001L;
  private static final long STORE_ID = 2001L;
  private static final List<String> IMAGES =
      List.of("/api/v1/media-public/open-im-local-public/saas/1001/202609/a.png",
          "/api/v1/media-public/open-im-local-public/saas/1001/202609/b.png");

  @MockitoBean
  private MqProducer mqProducer;

  @MockitoBean
  private MqConsumerFactory mqConsumerFactory;

  @MockitoBean
  private AuditClient auditClient;

  @MockitoBean
  private EventOutboxRelay eventOutboxRelay;

  @Autowired
  private ProductMapper productMapper;

  @Autowired
  private InventoryMaterialMapper materialMapper;

  @Autowired
  private CatalogItemMapper catalogItemMapper;

  @Autowired
  private DataSource dataSource;

  @BeforeEach
  void setUpTenant() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 0L, 0));
  }

  @AfterEach
  void tearDownTenant() {
    TenantContextHolder.clear();
  }

  @Test
  void productImagesRoundTripThroughJsonColumn() throws Exception {
    ProductPo product = new ProductPo();
    product.setTenantId(TENANT_ID);
    product.setStoreId(STORE_ID);
    product.setProductCode("IMG-P-" + System.nanoTime());
    product.setName("多图商品");
    product.setCategory("饮品");
    product.setUnit("瓶");
    product.setSalePrice(new BigDecimal("1200"));
    product.setStockControlled(false);
    product.setStatus("DRAFT");
    product.setSortOrder(0);
    product.setImageUrls(IMAGES);
    product.setMainImageUrl(IMAGES.get(1));
    product.setVersion(0);
    product.setCreatedAt(LocalDateTime.now());
    product.setUpdatedAt(LocalDateTime.now());
    productMapper.insert(product);

    ProductPo reloaded = productMapper.selectById(product.getId());
    assertEquals(IMAGES, reloaded.getImageUrls());
    assertEquals(IMAGES.get(1), reloaded.getMainImageUrl());
    assertTrue(rawJson("ord_product", product.getId()).startsWith("["), "image_urls 应落为 JSON 数组文本");
  }

  @Test
  void materialImagesRoundTripThroughJsonColumn() throws Exception {
    InventoryMaterialPo material = new InventoryMaterialPo();
    material.setTenantId(TENANT_ID);
    material.setStoreId(STORE_ID);
    material.setMaterialCode("IMG-M-" + System.nanoTime());
    material.setName("多图物料");
    material.setCategory("耗材");
    material.setUnit("包");
    material.setSafetyStock(BigDecimal.ZERO);
    material.setStatus("ACTIVE");
    material.setImageUrls(IMAGES);
    material.setMainImageUrl(IMAGES.get(0));
    material.setVersion(0);
    material.setCreatedAt(LocalDateTime.now());
    material.setUpdatedAt(LocalDateTime.now());
    materialMapper.insert(material);

    InventoryMaterialPo reloaded = materialMapper.selectById(material.getId());
    assertEquals(IMAGES, reloaded.getImageUrls());
    assertEquals(IMAGES.get(0), reloaded.getMainImageUrl());
    assertTrue(rawJson("ord_inventory_material", material.getId()).startsWith("["),
        "image_urls 应落为 JSON 数组文本");
  }

  /**
   * 点单目录项多图落库（生产 V14__ord_catalog_item_images.sql）：
   * H2 测试 schema 必须同步这张表与两列，否则商品同步目录项的点单链路直接报错。
   */
  @Test
  void catalogItemImagesRoundTripThroughJsonColumn() throws Exception {
    CatalogItemPo item = new CatalogItemPo();
    item.setTenantId(TENANT_ID);
    item.setStoreId(STORE_ID);
    item.setCategory("酒水");
    item.setItemType("PRODUCT");
    item.setName("多图目录项");
    item.setUnit("瓶");
    item.setUnitPrice(new BigDecimal("1500"));
    item.setStatus("ACTIVE");
    item.setStockControlled(false);
    item.setSortOrder(0);
    item.setImageUrls(IMAGES);
    item.setMainImageUrl(IMAGES.get(0));
    item.setCreatedAt(LocalDateTime.now());
    item.setUpdatedAt(LocalDateTime.now());
    catalogItemMapper.insert(item);

    CatalogItemPo reloaded = catalogItemMapper.selectById(item.getId());
    assertEquals(IMAGES, reloaded.getImageUrls());
    assertEquals(IMAGES.get(0), reloaded.getMainImageUrl());
    assertTrue(rawJson("ord_catalog_item", item.getId()).startsWith("["),
        "image_urls 应落为 JSON 数组文本");
  }

  /** 直接读原始列值，确认 JSON 数组文本确实写进了 image_urls（而非空值/转义异常）。 */
  private String rawJson(String table, Long id) throws Exception {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement();
         ResultSet resultSet = statement.executeQuery(
             "SELECT image_urls FROM " + table + " WHERE id = " + id)) {
      resultSet.next();
      return resultSet.getString(1);
    }
  }
}

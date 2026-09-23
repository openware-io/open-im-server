package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.MqProducer;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.controller.CatalogController;
import io.openware.platform.order.handler.GlobalExceptionHandler;
import io.openware.platform.order.infra.mq.EventOutboxRelay;
import io.openware.platform.order.infra.persistence.mapper.CatalogItemMapper;
import io.openware.platform.order.infra.persistence.mapper.ProductCategoryMapper;
import io.openware.platform.order.infra.persistence.mapper.ProductMapper;
import io.openware.platform.order.infra.persistence.po.CatalogItemPo;
import io.openware.platform.order.infra.persistence.po.ProductCategoryPo;
import io.openware.platform.order.infra.persistence.po.ProductPo;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 商品分类管理集成测试（真跑 H2 + Flyway + MyBatis）：
 * 分类 CRUD、同门店重名 409、被商品引用不可删、改名后商品列表与点单目录都跟着显示新分类名。
 * 用独立 STORE_ID 与其它集成测试隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
class ProductCategoryIntegrationTest {

  private static final long TENANT_ID = 1001L;
  private static final long STORE_ID = 2402L;

  @MockitoBean
  private MqProducer mqProducer;

  @MockitoBean
  private MqConsumerFactory mqConsumerFactory;

  @MockitoBean
  private AuditClient auditClient;

  @MockitoBean
  private EventOutboxRelay eventOutboxRelay;

  @Autowired
  private ProductCategoryApplicationService categoryService;

  @Autowired
  private ProductApplicationService productService;

  @Autowired
  private CatalogController catalogController;

  @Autowired
  private ProductCategoryMapper categoryMapper;

  @Autowired
  private ProductMapper productMapper;

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

  /** 分类新增/改名排序停用/删除，以及同门店重名 409。 */
  @Test
  void categoryCrudWorksAndDuplicateNameIsConflict() {
    String name = "集成分类-" + System.nanoTime();
    ProductCategoryPo category = categoryService.create(new ProductCategoryApplicationService.CategoryCommand(name, 1, null));
    assertNotNull(category.getId(), "新增后应有主键");
    assertEquals("ACTIVE", category.getStatus());
    assertTrue(listedCategoryIds("ACTIVE").contains(category.getId()), "新增的分类应出现在启用列表里");

    BusinessException duplicated = assertThrows(BusinessException.class, () -> categoryService.create(
        new ProductCategoryApplicationService.CategoryCommand(name, 2, null)));
    assertEquals("PRODUCT_CATEGORY_DUPLICATED", duplicated.getCode());
    assertEquals(HttpStatus.CONFLICT, new GlobalExceptionHandler().handleBusiness(duplicated).getStatusCode());

    categoryService.update(category.getId(), new ProductCategoryApplicationService.CategoryCommand(null, 9, "DISABLED"));
    assertEquals(9, categoryMapper.selectById(category.getId()).getSortOrder());
    assertEquals("DISABLED", categoryMapper.selectById(category.getId()).getStatus());
    assertTrue(!listedCategoryIds("ACTIVE").contains(category.getId()), "停用后不该出现在「只看启用」列表里");

    categoryService.delete(category.getId());
    assertNull(categoryMapper.selectById(category.getId()), "没被引用的分类可以删除");
  }

  /** 商品选分类后，商品列表与点单目录都显示该分类；分类改名后三处一起改。 */
  @Test
  void productAndCatalogFollowCategoryNameIncludingRename() {
    long suffix = System.nanoTime();
    String name = "集成酒水-" + suffix;
    ProductCategoryPo category = categoryService.create(new ProductCategoryApplicationService.CategoryCommand(name, 1, null));
    ProductPo product = productService.create(new ProductApplicationService.ProductCommand(
        STORE_ID, "CAT-P-" + suffix, "分类商品", name, "瓶", new BigDecimal("1000"), null, false, 0, null, null, null));
    assertEquals(name, product.getCategory());
    productService.onShelf(product.getId());

    ProductPo listed = productMapper.selectById(product.getId());
    assertEquals(name, listed.getCategory(), "商品列表要显示所选分类");
    CatalogItemPo catalogItem = catalogController.list(null, STORE_ID).stream()
        .filter(item -> item.getId().equals(product.getCatalogItemId())).findFirst().orElseThrow();
    assertEquals(name, catalogItem.getCategory(), "点单目录要显示所选分类");

    String renamed = name + "-改名";
    categoryService.update(category.getId(), new ProductCategoryApplicationService.CategoryCommand(renamed, 1, null));

    assertEquals(renamed, productMapper.selectById(product.getId()).getCategory(), "改名后商品引用要同步");
    assertEquals(renamed, catalogItemMapper.selectById(product.getCatalogItemId()).getCategory(), "改名后目录项引用要同步");
    assertEquals(renamed, catalogController.list(null, STORE_ID).stream()
        .filter(item -> item.getId().equals(product.getCatalogItemId())).findFirst().orElseThrow().getCategory());
  }

  /** 被商品引用时禁止删除（409），把商品改到别的分类后才能删。 */
  @Test
  void deleteIsRejectedWhileCategoryStillReferenced() {
    long suffix = System.nanoTime();
    String name = "集成在用-" + suffix;
    String fallback = "集成备用-" + suffix;
    ProductCategoryPo category = categoryService.create(new ProductCategoryApplicationService.CategoryCommand(name, 1, null));
    ProductPo product = productService.create(new ProductApplicationService.ProductCommand(
        STORE_ID, "CAT-P2-" + suffix, "在用分类商品", name, "瓶", new BigDecimal("1000"), null, false, 0, null, null, null));

    BusinessException inUse = assertThrows(BusinessException.class, () -> categoryService.delete(category.getId()));
    assertEquals("PRODUCT_CATEGORY_IN_USE", inUse.getCode());
    assertEquals("分类「%s」下仍有 1 个商品，请先改到其他分类再删除".formatted(name), inUse.getMessage());
    assertEquals(HttpStatus.CONFLICT, new GlobalExceptionHandler().handleBusiness(inUse).getStatusCode());
    assertNotNull(categoryMapper.selectById(category.getId()), "被引用时不能真删掉分类");

    categoryService.create(new ProductCategoryApplicationService.CategoryCommand(fallback, 2, null));
    productService.update(product.getId(), new ProductApplicationService.ProductCommand(
        null, null, null, fallback, null, null, null, null, null, null, null, null));

    categoryService.delete(category.getId());
    assertNull(categoryMapper.selectById(category.getId()));
  }

  private List<Long> listedCategoryIds(String status) {
    return categoryService.list(STORE_ID, status).stream().map(ProductCategoryPo::getId).toList();
  }
}

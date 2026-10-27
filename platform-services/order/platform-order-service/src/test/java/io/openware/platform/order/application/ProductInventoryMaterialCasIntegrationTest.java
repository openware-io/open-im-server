package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.MqProducer;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.domain.catalog.model.Product;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.domain.inventory.model.InventoryMaterial;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository;
import io.openware.platform.order.infra.client.PaymentCollectedClient;
import io.openware.platform.order.infra.client.ResourceStateClient;
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
 * 真实 H2 + MyBatis + Flyway：证明 Product / InventoryMaterial 的 CAS 不是 mock 出来的。
 *
 * <p>每条用例都断言三件事：首次按 version 0 命中并自增；库里版本变为 1；用旧版本再写返回 409。
 */
@SpringBootTest
@ActiveProfiles("test")
class ProductInventoryMaterialCasIntegrationTest {

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

  @MockitoBean
  private ResourceStateClient resourceStateClient;

  @MockitoBean
  private PaymentCollectedClient paymentCollectedClient;

  @Autowired
  private ProductRepository productRepository;

  @Autowired
  private InventoryMaterialRepository materialRepository;

  @BeforeEach
  void setUpTenant() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 0L, 0, java.util.List.of(), null, "KTV", null));
  }

  @AfterEach
  void tearDownTenant() {
    TenantContextHolder.clear();
  }

  @Test
  void productUpdateIncrementsVersionAndRejectsStaleVersion() {
    Product created = productRepository.insert(Product.draft(
        TENANT_ID, STORE_ID, "P-CAS-001", "CAS 测试商品", "测试分类", "PRODUCT", "件",
        new BigDecimal("12.50"), null, null, false, 0, null, List.of(), null)).aggregate();
    assertEquals(0, created.version());

    Product updated = productRepository.update(created.withStatus(Product.STATUS_ON_SHELF), 0).aggregate();

    assertEquals(1, updated.version());
    assertEquals(1, productRepository.findById(created.id()).orElseThrow().version());
    IllegalStateException conflict = assertThrows(IllegalStateException.class,
        () -> productRepository.update(created.withStatus(Product.STATUS_OFF_SHELF), 0));
    assertEquals("PRODUCT_VERSION_CONFLICT", conflict.getMessage());
  }

  @Test
  void materialUpdateIncrementsVersionAndRejectsStaleVersion() {
    InventoryMaterial created = materialRepository.insert(InventoryMaterial.draft(
        TENANT_ID, STORE_ID, "M-CAS-001", "CAS 测试物料", "测试分类", "件", null,
        BigDecimal.ZERO, BigDecimal.ONE, "CNY", List.of(), null)).aggregate();
    assertEquals(0, created.version());

    InventoryMaterial updated = materialRepository.update(created, 0).aggregate();

    assertEquals(1, updated.version());
    assertEquals(1, materialRepository.findById(created.id()).orElseThrow().version());
    IllegalStateException conflict = assertThrows(IllegalStateException.class,
        () -> materialRepository.update(created, 0));
    assertEquals("INVENTORY_MATERIAL_VERSION_CONFLICT", conflict.getMessage());
  }
}

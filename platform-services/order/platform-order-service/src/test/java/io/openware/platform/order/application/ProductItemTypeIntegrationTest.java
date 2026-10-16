package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.MqProducer;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.controller.CatalogController;
import io.openware.platform.order.api.dto.CatalogItemView;
import io.openware.platform.order.api.dto.ProductView;
import io.openware.platform.order.domain.catalog.model.CatalogItem;
import io.openware.platform.order.domain.catalog.model.Product;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.infra.client.ResourceStateClient.ServerSnapshot;
import io.openware.platform.order.infra.mq.EventOutboxRelay;
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
 * 商品类型（实物 / 服务）落库与点单目录可见性集成测试（真跑 H2 + Flyway + MyBatis）：
 *  1) 迁移列 {@code item_type} / {@code server_resource_id} 写入正确，历史/老调用方一律 PRODUCT；
 *  2) 服务型商品同步出的目录项是 {@code item_type=SERVICE} 且不占库存，上架后普通加项就能点到；
 *  3) 未关联商品的「纯服务目录项」也可点（加项能对服务加项），而纯商品目录项仍被隐藏。
 *
 * <p><b>迁移后的读口径</b>：商品与目录项都经领域端口读取（领域模型），
 * 列表响应读 {@link CatalogItemView}。
 */
@SpringBootTest
@ActiveProfiles("test")
class ProductItemTypeIntegrationTest {

    private static final long TENANT_ID = 1001L;
    private static final long STORE_ID = 2601L;
    /** 服务人员 ID 每个用例独占（唯一键 uk_ord_product_server_resource 不允许同门店复用）。 */
    private static final java.util.concurrent.atomic.AtomicLong SERVER_RESOURCE_IDS =
            new java.util.concurrent.atomic.AtomicLong(9100L);

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

    @Autowired
    private ProductApplicationService productService;

    @Autowired
    private CatalogController catalogController;

    @Autowired
    private CatalogItemRepository catalogItemRepository;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void setUpTenant() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 0L, 0));
        // 每个用例用各自的服务人员 ID，避免共享 H2 里被上一个用例的商品占住（唯一键）。
        when(resourceStateClient.requireServer(anyLong()))
                .thenAnswer(invocation -> new ServerSnapshot(invocation.getArgument(0, Long.class),
                        "小美", "S01", "KTV_SERVER", STORE_ID, "ENABLED"));
    }

    @AfterEach
    void tearDownTenant() {
        TenantContextHolder.clear();
    }

    /** 服务型商品：商品行落 SERVICE + 服务人员 ID，目录项同步 SERVICE 且不占库存。 */
    @Test
    void serviceProductPersistsItemTypeAndServerAndSyncsServiceCatalogItem() {
        long serverResourceId = SERVER_RESOURCE_IDS.incrementAndGet();
        ProductView product = productService.create(serviceCommand("ITT-S-" + System.nanoTime(), serverResourceId));

        Product stored = loadProduct(product.id());
        assertEquals("SERVICE", stored.itemType());
        assertEquals(serverResourceId, stored.serverResourceId());
        assertEquals(Boolean.FALSE, stored.stockControlled());
        assertNull(stored.materialId());

        CatalogItem catalog = loadCatalogItem(product.catalogItemId());
        assertNotNull(catalog, "商品创建后应同步出目录项");
        assertEquals("SERVICE", catalog.itemType());
        assertEquals(Boolean.FALSE, catalog.stockControlled(), "服务目录项绝不参与库存");
        assertEquals("INACTIVE", catalog.status(), "草稿服务商品的目录项不可点");
    }

    /** 迁移默认值：不传 itemType 的老调用方（历史行同口径）落库一律 PRODUCT。 */
    @Test
    void productWithoutItemTypeIsStoredAsProduct() {
        ProductView product = productService.create(new ProductApplicationService.ProductCommand(
                STORE_ID, "ITT-P-" + System.nanoTime(), "历史口径商品", "饮品", "瓶", new BigDecimal("1200"),
                null, false, 0, null, null, null));

        Product stored = loadProduct(product.id());
        assertEquals("PRODUCT", stored.itemType());
        assertNull(stored.serverResourceId());
    }

    /** 服务商品上架后，普通加项链路（点单目录）就能看到它，且不因库存被置灰。 */
    @Test
    void serviceProductIsOrderableAfterOnShelf() {
        ProductView product = productService.create(serviceCommand("ITT-S2-" + System.nanoTime(),
                SERVER_RESOURCE_IDS.incrementAndGet()));
        productService.onShelf(product.id());

        CatalogItemView visible = visibleItems().stream()
                .filter(item -> item.id().equals(product.catalogItemId()))
                .findFirst().orElse(null);

        assertNotNull(visible, "上架后的服务商品必须出现在点单目录里");
        assertEquals("SERVICE", visible.itemType());
        assertEquals(Boolean.TRUE, visible.available(), "服务不校验库存，必须可点");
        assertNull(visible.unavailableReason());
        // 列是 decimal(20,6)：按数值比较（100 与 100.000000 必须视为相等）。
        assertEquals(0, visible.unitPrice().compareTo(new BigDecimal("100")), "服务加项按目录项单价计费");
    }

    /**
     * 加项能对服务加项：直接建（未关联商品）的纯服务目录项也可点；
     * 而未关联商品的纯商品目录项仍被隐藏（实物商品必须走商品 + 库存口径）。
     */
    @Test
    void standaloneServiceCatalogItemIsOrderableButStandaloneProductItemIsNot() {
        CatalogItem service = catalogItem("SERVICE", "清洁服务", "次", new BigDecimal("3000"));
        CatalogItem product = catalogItem("PRODUCT", "无主商品", "瓶", new BigDecimal("1500"));

        List<Long> visibleIds = visibleItems().stream().map(CatalogItemView::id).toList();

        assertTrue(visibleIds.contains(service.id()), "未关联商品的纯服务目录项必须可点（加项能对服务加项）");
        assertFalse(visibleIds.contains(product.id()), "未关联商品的商品目录项仍不可点");
    }

    private static ProductApplicationService.ProductCommand serviceCommand(String code, long serverResourceId) {
        return new ProductApplicationService.ProductCommand(STORE_ID, code, "陪唱服务", "服务", "次",
                new BigDecimal("100"), null, false, 0, null, null, null, "SERVICE", serverResourceId);
    }

    private CatalogItem catalogItem(String itemType, String name, String unit, BigDecimal price) {
        return catalogItemRepository.insert(new CatalogItem(null, null, TENANT_ID, STORE_ID, "服务", itemType,
            name + "-" + System.nanoTime(), unit, price, null, List.of(), null, "ACTIVE", false, 1)).aggregate();
    }

    private Product loadProduct(Long id) {
        Optional<Product> found = productRepository.findById(id);
        assertNotNull(found.orElse(null), "商品应存在: " + id);
        return found.orElseThrow();
    }

    private CatalogItem loadCatalogItem(Long id) {
        Optional<CatalogItem> found = catalogItemRepository.findById(id);
        assertNotNull(found.orElse(null), "目录项应存在: " + id);
        return found.orElseThrow();
    }

    private List<CatalogItemView> visibleItems() {
        return catalogController.list(null, STORE_ID);
    }
}

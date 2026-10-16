package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.dto.ProductView;
import io.openware.platform.order.domain.catalog.model.CatalogItem;
import io.openware.platform.order.domain.catalog.model.Product;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.AuditSnapshot;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.Persisted;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.infra.client.ResourceStateClient.ServerSnapshot;
import io.openware.platform.order.domain.inventory.model.InventoryMaterial;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 商品分「实物 / 服务」的口径单测（第 3 点）：
 *  - 服务型商品必须关联「存在 + KTV_SERVER + 同门店 + 启用中 + 未被占用」的服务人员；
 *  - 实物商品忽略服务人员字段（不做任何资源域调用，落库为 NULL）；
 *  - 目录项 itemType / stock_controlled 必须跟着商品回写（加项与点单共用同一份 SERVICE 目录项）；
 *  - 服务人员名称是展示用的降级读：资源服务不可达时名称 null、列表照常返回
 *    （它**不是列**，只存在于 {@link ProductView}）。
 */
class ProductItemTypeApplicationServiceTest {

    private static final long TENANT_ID = 1L;
    private static final long STORE_ID = 3L;

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final CatalogItemRepository catalogItemRepository = mock(CatalogItemRepository.class);
    private final InventoryMaterialRepository materialRepository = mock(InventoryMaterialRepository.class);
    private final ResourceStateClient resourceStateClient = mock(ResourceStateClient.class);
    private final ProductApplicationService service = new ProductApplicationService(
            productRepository, catalogItemRepository, materialRepository, AuditClient.disabled(), resourceStateClient);

    @BeforeEach
    void stubPersistence() {
        when(productRepository.insert(any(Product.class))).thenAnswer(invocation ->
                persisted(invocation.getArgument(0, Product.class)));
        when(productRepository.update(any(Product.class))).thenAnswer(invocation ->
                persisted(invocation.getArgument(0, Product.class)));
        when(catalogItemRepository.insert(any(CatalogItem.class))).thenAnswer(invocation ->
                persisted(invocation.getArgument(0, CatalogItem.class).withId(77L)));
        when(catalogItemRepository.update(any(CatalogItem.class))).thenAnswer(invocation ->
                persisted(invocation.getArgument(0, CatalogItem.class)));
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    // —— 服务型商品：服务人员必填与校验 ————————————————————————————————

    @Test
    void createServiceProductWithoutServerIsRejected() {
        tenant();

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(
                serviceCommand(null)));

        assertEquals("PRODUCT_SERVICE_SERVER_REQUIRED", error.getCode());
        assertEquals("服务商品必须关联服务人员", error.getMessage());
        verify(productRepository, never()).insert(any(Product.class));
    }

    @Test
    void createServiceProductRejectsServerFromAnotherStore() {
        tenant();
        givenServer(9L, "KTV_SERVER", 999L, "ENABLED");

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(
                serviceCommand(9L)));

        assertEquals("SERVER_RESOURCE_STORE_MISMATCH", error.getCode());
    }

    @Test
    void createServiceProductRejectsNonServerResource() {
        tenant();
        // 选了包厢（KTV_ROOM）当服务人员：必须拒绝，否则会把包厢挂成服务商品。
        givenServer(9L, "KTV_ROOM", STORE_ID, "ENABLED");

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(
                serviceCommand(9L)));

        assertEquals("SERVER_RESOURCE_TYPE_INVALID", error.getCode());
    }

    @Test
    void createServiceProductRejectsDisabledServer() {
        tenant();
        givenServer(9L, "KTV_SERVER", STORE_ID, "DISABLED");

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(
                serviceCommand(9L)));

        assertEquals("SERVER_RESOURCE_DISABLED", error.getCode());
    }

    /** 同一服务人员被两个服务商品占用：应用层先给可读的 409，数据库唯一键只是并发兜底。 */
    @Test
    void createServiceProductRejectsServerAlreadyLinkedToAnotherProduct() {
        tenant();
        givenServer(9L, "KTV_SERVER", STORE_ID, "ENABLED");
        when(productRepository.findServerResourceOccupant(eq(TENANT_ID), eq(STORE_ID), eq("SERVICE"), eq(9L), eq(null)))
                .thenReturn(Optional.of(new Product(88L, TENANT_ID, STORE_ID, "P-88", "已有服务商品", "服务",
                        "SERVICE", "次", new BigDecimal("100"), null, 9L, false, null, "DRAFT", 0, null, List.of(),
                        null, 0)));

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(
                serviceCommand(9L)));

        assertEquals("SERVER_RESOURCE_IN_USE", error.getCode());
        assertEquals("该服务人员已关联服务商品「已有服务商品」，请先解除原关联", error.getMessage());
    }

    @Test
    void createRejectsUnknownItemType() {
        tenant();

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(
                new ProductApplicationService.ProductCommand(STORE_ID, "S-1", "套餐装", "其他", "份",
                        new BigDecimal("100"), null, false, 0, null, null, null, "PACKAGE", null)));

        assertEquals("PRODUCT_ITEM_TYPE_INVALID", error.getCode());
    }

    /** 服务型商品：强制不占库存、无物料，服务人员落到商品上，并同步出 item_type=SERVICE 的目录项。 */
    @Test
    void createServiceProductPersistsServerAndSyncsServiceCatalogItem() {
        tenant();
        givenServer(9L, "KTV_SERVER", STORE_ID, "ENABLED");

        ProductView created = service.create(serviceCommand(9L));

        assertEquals("SERVICE", created.itemType());
        assertEquals(9L, created.serverResourceId());
        assertEquals("小美", created.serverResourceName());
        assertEquals(Boolean.FALSE, created.stockControlled(), "服务不占库存");
        assertNull(created.materialId(), "服务不需要仓库商品");

        ArgumentCaptor<CatalogItem> captor = ArgumentCaptor.forClass(CatalogItem.class);
        verify(catalogItemRepository).insert(captor.capture());
        CatalogItem catalog = captor.getValue();
        assertEquals("SERVICE", catalog.itemType(), "目录项类型必须与商品一致（加项与服务人员点单共用同一份）");
        assertEquals(Boolean.FALSE, catalog.stockControlled());
        assertEquals(new BigDecimal("100"), catalog.unitPrice(), "服务目录项单价沿用商品售价");
    }

    // —— 实物商品：忽略服务人员字段 ————————————————————————————————

    @Test
    void createPhysicalProductIgnoresServerResourceWithoutCallingResourceService() {
        tenant();
        when(materialRepository.findInStore(TENANT_ID, STORE_ID, 55L))
                .thenReturn(Optional.of(activeMaterial(55L)));

        ProductView created = service.create(new ProductApplicationService.ProductCommand(STORE_ID, "P-1", "可乐", "饮品",
                "瓶", new BigDecimal("100"), 55L, true, 0, null, null, null, "PRODUCT", 9L));

        assertEquals("PRODUCT", created.itemType());
        assertNull(created.serverResourceId(), "实物商品必须忽略服务人员字段");
        verifyNoInteractions(resourceStateClient);
    }

    /** 不传 itemType 的既有调用方（老前端/老单测）继续按实物商品处理。 */
    @Test
    void createWithoutItemTypeDefaultsToPhysicalProduct() {
        tenant();

        ProductView created = service.create(new ProductApplicationService.ProductCommand(STORE_ID, "P-2", "加钟", "其他",
                "次", new BigDecimal("100"), null, false, 0, null, null, null));

        assertEquals("PRODUCT", created.itemType());
        verifyNoInteractions(resourceStateClient);
    }

    // —— 编辑态：类型切换 ————————————————————————————————————————

    @Test
    void updateSwitchingToServiceRequiresServer() {
        tenant();
        when(productRepository.findById(10L)).thenReturn(Optional.of(physicalProduct(10L)));

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(10L,
                new ProductApplicationService.ProductCommand(null, null, null, null, null, null, null, null, null,
                        null, null, null, "SERVICE", null)));

        assertEquals("PRODUCT_SERVICE_SERVER_REQUIRED", error.getCode());
    }

    /** 服务 → 实物：清空服务人员（释放唯一键），库存/物料校验回到原口径。 */
    @Test
    void updateSwitchingBackToPhysicalClearsServerAndMaterialRulesApply() {
        tenant();
        Product serviceEntity = serviceProduct(10L, 5L);
        when(productRepository.findById(10L)).thenReturn(Optional.of(serviceEntity));
        when(catalogItemRepository.findById(77L)).thenReturn(Optional.of(catalogItem(77L, "SERVICE")));
        when(materialRepository.findInStore(TENANT_ID, STORE_ID, 55L))
                .thenReturn(Optional.of(activeMaterial(55L)));

        ProductView updated = service.update(10L, new ProductApplicationService.ProductCommand(
                null, null, null, null, null, null, 55L, true, null, null, null, null, "PRODUCT", 5L));

        assertEquals("PRODUCT", updated.itemType());
        assertNull(updated.serverResourceId(), "切回实物必须清空服务人员");
        assertEquals(55L, updated.materialId());
        ArgumentCaptor<CatalogItem> captor = ArgumentCaptor.forClass(CatalogItem.class);
        verify(catalogItemRepository, org.mockito.Mockito.atLeastOnce()).update(captor.capture());
        assertEquals("PRODUCT", captor.getValue().itemType(), "目录项类型必须跟着切回 PRODUCT");
        verifyNoInteractions(resourceStateClient);
    }

    /** 服务 → 服务（改名/改价，命令不带 serverResourceId）：沿用既有服务人员，不重复校验占用自己。 */
    @Test
    void updateServiceProductKeepsExistingServer() {
        tenant();
        when(productRepository.findById(10L)).thenReturn(Optional.of(serviceProduct(10L, 5L)));
        givenServer(5L, "KTV_SERVER", STORE_ID, "ENABLED");

        ProductView updated = service.update(10L, new ProductApplicationService.ProductCommand(
                null, null, "新名字", null, null, null, null, null, null, null, null, null, null, null));

        assertEquals(5L, updated.serverResourceId());
        assertEquals("新名字", updated.name());
    }

    @Test
    void onShelfRejectsServiceProductWithoutServer() {
        tenant();
        when(productRepository.findById(10L)).thenReturn(Optional.of(serviceProduct(10L, null)));

        BusinessException error = assertThrows(BusinessException.class, () -> service.onShelf(10L));

        assertEquals("PRODUCT_SERVICE_SERVER_REQUIRED", error.getCode());
    }

    // —— 列表：服务人员名称（读路径降级） ————————————————————————————

    @Test
    void listFillsServerNameForServiceProducts() {
        tenant();
        when(productRepository.find(any(ProductRepository.ProductQuery.class)))
                .thenReturn(List.of(serviceProduct(10L, 5L)));
        when(resourceStateClient.resources(eq("KTV_SERVER"), eq(STORE_ID)))
                .thenReturn(List.of(new ServerSnapshot(5L, "小美", "S01", "KTV_SERVER", STORE_ID, "ENABLED")));

        List<ProductView> rows = service.list(STORE_ID, null, null);

        assertEquals("小美", rows.get(0).serverResourceName());
    }

    /** 资源服务不可达：名称降级为 null，列表照常返回（绝不 5xx）。 */
    @Test
    void listDegradesServerNameWhenResourceServiceUnavailable() {
        tenant();
        when(productRepository.find(any(ProductRepository.ProductQuery.class)))
                .thenReturn(List.of(serviceProduct(10L, 5L)));
        when(resourceStateClient.resources(anyString(), any())).thenThrow(new IllegalStateException("resource down"));

        List<ProductView> rows = service.list(STORE_ID, null, null);

        assertEquals(1, rows.size());
        assertNull(rows.get(0).serverResourceName());
        assertEquals(5L, rows.get(0).serverResourceId(), "ID 仍在，前端可显示「服务人员 #5」");
    }

    // —— helpers ————————————————————————————————————————————————

    private static void tenant() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
    }

    private void givenServer(Long id, String resourceType, Long storeId, String status) {
        when(resourceStateClient.requireServer(id))
                .thenReturn(new ServerSnapshot(id, "小美", "S01", resourceType, storeId, status));
    }

    private static ProductApplicationService.ProductCommand serviceCommand(Long serverResourceId) {
        return new ProductApplicationService.ProductCommand(STORE_ID, "S-" + serverResourceId, "陪唱服务", "服务",
                "次", new BigDecimal("100"), null, false, 0, null, null, null, "SERVICE", serverResourceId);
    }

    private static Product physicalProduct(Long id) {
        return new Product(id, TENANT_ID, STORE_ID, "P-" + id, "可乐", "饮品", "PRODUCT", "瓶",
                new BigDecimal("100"), null, null, true, null, "DRAFT", 0, null, List.of(), null, 0);
    }

    /** 服务型商品（库存开关强制 false，可带/不带服务人员；{@code catalog_item_id=77}）。 */
    private static Product serviceProduct(Long id, Long serverResourceId) {
        return new Product(id, TENANT_ID, STORE_ID, "S-" + id, "陪唱服务", "服务", "SERVICE", "次",
                new BigDecimal("100"), null, serverResourceId, false, serverResourceId == null ? null : 77L,
                "DRAFT", 0, null, List.of(), null, 0);
    }

    private static CatalogItem catalogItem(Long id, String itemType) {
        return new CatalogItem(id, null, TENANT_ID, STORE_ID, "服务", itemType, "陪唱服务", "次",
                new BigDecimal("100"), null, List.of(), null, "INACTIVE", false, 0);
    }

    /** 一行启用的物料领域模型（跨子域窄读的夹具：端口返回 Optional）。 */
    private static InventoryMaterial activeMaterial(Long id) {
        return new InventoryMaterial(id, TENANT_ID, STORE_ID, "M-" + id, "物料" + id, "耗材", "件", null,
                BigDecimal.ZERO, null, "USD", null, null, "ACTIVE", 0, LocalDateTime.now());
    }

    /** 端口返回值：聚合 + 审计快照（领域模型不承载审计列）。 */
    private static <T> Persisted<T> persisted(T aggregate) {
        LocalDateTime now = LocalDateTime.of(2026, 9, 19, 20, 0, 0);
        return new Persisted<>(aggregate, new AuditSnapshot(null, now, null, now));
    }
}

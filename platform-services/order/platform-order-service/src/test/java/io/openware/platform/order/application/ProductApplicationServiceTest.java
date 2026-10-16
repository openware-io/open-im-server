package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.dto.ProductView;
import io.openware.platform.order.domain.catalog.model.CatalogItem;
import io.openware.platform.order.domain.catalog.model.Product;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.AuditSnapshot;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.Persisted;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.domain.inventory.model.InventoryMaterial;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 商品多图与主图规则单测：落库字段、主图归属、9 张上限。
 *
 * <p><b>迁移后的断言口径</b>：商品/目录项改经领域端口，夹具是**不可变 record**
 * （没有 setter），因此「编辑后的字段」从返回值（{@link ProductView}）读、
 * 「确实落库」从端口调用参数读，而不是对某个 PO 断言就地改名。
 * 跨子域读（关联物料是否存在/启用、物料图片回退）同样走
 * {@link InventoryMaterialRepository} 端口。
 */
class ProductApplicationServiceTest {

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final CatalogItemRepository catalogItemRepository = mock(CatalogItemRepository.class);
    private final InventoryMaterialRepository materialRepository = mock(InventoryMaterialRepository.class);
    private final ProductApplicationService service =
            new ProductApplicationService(productRepository, catalogItemRepository, materialRepository);

    @BeforeEach
    void stubPersistence() {
        // 端口返回值：聚合 + 审计快照。用调用参数回填，模拟「适配器回写主键」。
        when(productRepository.insert(any(Product.class))).thenAnswer(invocation ->
                persisted(invocation.getArgument(0, Product.class), 10L));
        when(productRepository.update(any(Product.class))).thenAnswer(invocation ->
                persisted(invocation.getArgument(0, Product.class), null));
        // 目录项新增必须回填主键：syncCatalog 靠它写商品侧的 catalog_item_id 回指。
        when(catalogItemRepository.insert(any(CatalogItem.class))).thenAnswer(invocation ->
                persisted(invocation.getArgument(0, CatalogItem.class).withId(77L), null));
        when(catalogItemRepository.update(any(CatalogItem.class))).thenAnswer(invocation ->
                persisted(invocation.getArgument(0, CatalogItem.class), null));
    }

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    @Test
    void createPersistsImagesAndDefaultsMainImageToFirst() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        ProductView created = service.create(command(List.of("/api/v1/media-public/b/saas/1/1.png", "/api/v1/media-public/b/saas/1/2.png"), null));

        assertEquals(List.of("/api/v1/media-public/b/saas/1/1.png", "/api/v1/media-public/b/saas/1/2.png"), created.imageUrls());
        assertEquals("/api/v1/media-public/b/saas/1/1.png", created.mainImageUrl());
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).insert(captor.capture());
        assertEquals(List.of("/api/v1/media-public/b/saas/1/1.png", "/api/v1/media-public/b/saas/1/2.png"),
                captor.getValue().imagesOrEmpty());
    }

    @Test
    void createTrimsAndDeduplicatesImages() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        ProductView created = service.create(command(List.of(" /a.png ", "/a.png", "", "  "), null));

        assertEquals(List.of("/a.png"), created.imageUrls());
        assertEquals("/a.png", created.mainImageUrl());
    }

    @Test
    void createRejectsMainImageOutsideImages() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(command(List.of("/a.png"), "/b.png")));

        assertEquals("PRODUCT_INVALID", error.getCode());
        assertEquals("主图必须是已上传图片中的一张", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    @Test
    void createRejectsMoreThanNineImages() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        List<String> tenImages = IntStream.rangeClosed(1, 10).mapToObj(index -> "/" + index + ".png").toList();

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(command(tenImages, null)));

        assertEquals("PRODUCT_INVALID", error.getCode());
        assertEquals("图片最多 9 张，当前 10 张", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    @Test
    void createRejectsTooLongImageUrl() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(command(List.of("/" + "a".repeat(512) + ".png"), null)));

        assertEquals("PRODUCT_INVALID", error.getCode());
        verifyNoInteractions(productRepository);
    }

    @Test
    void updateReplacesImagesAndMainImage() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(existing(10L)));

        ProductView updated = service.update(10L,
                new ProductApplicationService.ProductCommand(null, null, "改名", null, null, null, null, null, null, null,
                        List.of("/x.png", "/y.png"), "/y.png"));

        assertEquals(List.of("/x.png", "/y.png"), updated.imageUrls());
        assertEquals("/y.png", updated.mainImageUrl());
        verify(productRepository, org.mockito.Mockito.atLeastOnce()).update(any(Product.class));
    }

    @Test
    void updateClearsImagesWhenListIsEmpty() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(existing(10L)
                .withImages(List.of("/old.png"), "/old.png")));

        ProductView updated = service.update(10L,
                new ProductApplicationService.ProductCommand(null, null, null, null, null, null, null, null, null, null,
                        List.of(), null));

        assertEquals(List.of(), updated.imageUrls());
        assertNull(updated.mainImageUrl());
    }

    @Test
    void updateRejectsMainImageOutsideImages() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(existing(10L)));

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(10L,
                new ProductApplicationService.ProductCommand(null, null, null, null, null, null, null, null, null, null,
                        List.of("/x.png"), "/y.png")));

        assertEquals("PRODUCT_INVALID", error.getCode());
        verify(productRepository, never()).update(any(Product.class));
    }

    @Test
    void createWithoutImagesKeepsNullMainImage() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        ProductView created = service.create(command(null, null));

        assertEquals(List.of(), created.imageUrls());
        assertNull(created.mainImageUrl());
    }

    /** 改商品图片后，目录项（点单列表的数据源）必须同步到新图。 */
    @Test
    void updateSyncsImagesToCatalogItem() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(existing(10L).withCatalogItemId(77L)));
        when(catalogItemRepository.findById(77L)).thenReturn(Optional.of(catalogItem(77L)));

        service.update(10L, new ProductApplicationService.ProductCommand(null, null, "可乐", null, null, null,
                null, null, null, null, List.of("/p-a.png", "/p-b.png"), "/p-b.png"));

        ArgumentCaptor<CatalogItem> captor = ArgumentCaptor.forClass(CatalogItem.class);
        verify(catalogItemRepository).update(captor.capture());
        assertEquals(List.of("/p-a.png", "/p-b.png"), captor.getValue().imagesOrEmpty());
        assertEquals("/p-b.png", captor.getValue().mainImageUrl());
    }

    /** 新建商品时就带上图（目录项随商品创建），上架后点单页直接有缩略图。 */
    @Test
    void createSyncsImagesToNewCatalogItem() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        service.create(command(List.of("/a.png", "/b.png"), "/b.png"));

        ArgumentCaptor<CatalogItem> captor = ArgumentCaptor.forClass(CatalogItem.class);
        verify(catalogItemRepository).insert(captor.capture());
        assertEquals(List.of("/a.png", "/b.png"), captor.getValue().imagesOrEmpty());
        assertEquals("/b.png", captor.getValue().mainImageUrl());
    }

    /** 商品自己没有图时，目录项用它关联物料的图（物料加项场景图片只维护在物料上）。 */
    @Test
    void catalogImagesFallBackToLinkedMaterialWhenProductHasNoImages() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(
                existing(10L).withCatalogItemId(77L).edited("可乐", "饮品", "瓶", new BigDecimal("100"), "PRODUCT",
                        null, false, 55L, null, List.of(), null)));
        when(catalogItemRepository.findById(77L)).thenReturn(Optional.of(catalogItem(77L)));
        when(materialRepository.findById(55L)).thenReturn(Optional.of(material(55L, "ACTIVE",
                List.of("/m-a.png"), "/m-a.png")));

        service.update(10L, new ProductApplicationService.ProductCommand(null, null, null, null, null, null,
                null, null, null, null, null, null));

        ArgumentCaptor<CatalogItem> captor = ArgumentCaptor.forClass(CatalogItem.class);
        verify(catalogItemRepository).update(captor.capture());
        assertEquals(List.of("/m-a.png"), captor.getValue().imagesOrEmpty());
        assertEquals("/m-a.png", captor.getValue().mainImageUrl());
    }

    /** 实物商品（占用库存）必须关联仓库商品：创建入口。 */
    @Test
    void createRejectsStockControlledProductWithoutMaterial() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(
                new ProductApplicationService.ProductCommand(3L, "P-1", "可乐", "饮品", "瓶", new BigDecimal("100"),
                        null, true, 0, null, null, null)));

        assertEquals("PRODUCT_INVALID", error.getCode());
        assertEquals("实物商品必须关联仓库商品", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    /** 非实物商品（服务/加项）不需要关联仓库商品，默认也必须能建。 */
    @Test
    void createAllowsNonStockControlledProductWithoutMaterial() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        ProductView created = service.create(command(null, null));

        assertEquals("DRAFT", created.status());
        assertNull(created.materialId());
    }

    @Test
    void createPersistsDescription() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        ProductView created = service.create(new ProductApplicationService.ProductCommand(
                3L, "P-2", "可乐", "饮品", "瓶", new BigDecimal("100"), null, false, 0, "冰镇可口可乐 330ml", null, null));

        assertEquals("冰镇可口可乐 330ml", created.description());
    }

    /** 描述超长必须 400 PRODUCT_INVALID + 明确中文消息，不能落库被数据库截断。 */
    @Test
    void createRejectsTooLongDescription() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(
                new ProductApplicationService.ProductCommand(3L, "P-3", "可乐", "饮品", "瓶", new BigDecimal("100"),
                        null, false, 0, "描".repeat(256), null, null)));

        assertEquals("PRODUCT_INVALID", error.getCode());
        assertEquals("描述长度不能超过 255 个字符", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    /**
     * 空白描述必须被归一化成 null 并交给端口。
     *
     * <p><b>断言口径的变更（不是放宽）</b>：迁移前这里断言的是「写出后的 PO 上 description 为 null」，
     * 而 {@code updateById} 的 {@code NOT_NULL} 策略让 null 列根本不进 SET——也就是说，
     * 那条断言在真实数据库上从未成立（它只在 mock mapper 上成立）。迁移后断言的是应用层的真实产物：
     * 端口收到了 {@code description == null} 的聚合。适配器保持同一 NOT_NULL 行为（见
     * {@code ProductRepositoryAdapter#update}），因此「清不掉」这个既有缺陷没有被本次迁移悄悄修掉，
     * 而是被明确记录下来。
     */
    @Test
    void updateClearsDescriptionWithBlankText() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(existing(10L)
                .edited("可乐", "饮品", "瓶", new BigDecimal("100"), "PRODUCT", null, false, null, "旧描述",
                        List.of(), null)));

        service.update(10L, new ProductApplicationService.ProductCommand(
                null, null, null, null, null, null, null, null, null, "   ", null, null));

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        // syncCatalog 在新建目录项后也会写回商品（catalog_item_id 回指），因此这里是至少一次。
        verify(productRepository, org.mockito.Mockito.atLeastOnce()).update(captor.capture());
        assertNull(captor.getAllValues().getFirst().description());
    }

    /** 更新入口：商品已经是实物但没关联仓库商品时，任何更新都必须被挡下（且不落库）。 */
    @Test
    void updateRejectsStockControlledProductWithoutMaterial() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(existing(10L)
                .edited("可乐", "饮品", "瓶", new BigDecimal("100"), "PRODUCT", null, true, null, null,
                        List.of(), null)));

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(10L,
                new ProductApplicationService.ProductCommand(null, null, "改名", null, null, null, null, null, null, null, null, null)));

        assertEquals("PRODUCT_INVALID", error.getCode());
        assertEquals("实物商品必须关联仓库商品", error.getMessage());
        verify(productRepository, never()).update(any(Product.class));
    }

    /** 更新入口：把非实物商品切换成实物商品时，没选仓库商品同样 400。 */
    @Test
    void updateRejectsSwitchingToStockControlledWithoutMaterial() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(existing(10L)));

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(10L,
                new ProductApplicationService.ProductCommand(null, null, null, null, null, null, null, true, null, null, null, null)));

        assertEquals("PRODUCT_INVALID", error.getCode());
        assertEquals("实物商品必须关联仓库商品", error.getMessage());
    }

    /** 切换成实物商品并选了仓库商品时，开关与关联都必须落库。 */
    @Test
    void updatePersistsStockControlledSwitchWithMaterial() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(existing(10L)));
        when(materialRepository.findInStore(1L, 3L, 55L)).thenReturn(Optional.of(material(55L, "ACTIVE", null, null)));

        ProductView updated = service.update(10L, new ProductApplicationService.ProductCommand(
                null, null, null, null, null, null, 55L, true, null, null, null, null));

        assertEquals(Boolean.TRUE, updated.stockControlled());
        assertEquals(55L, updated.materialId());
    }

    /** 上架入口：实物商品没有关联仓库商品时必须是 400 PRODUCT_INVALID + 同一句消息。 */
    @Test
    void onShelfRejectsStockControlledProductWithoutMaterial() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(existing(10L)
                .edited("可乐", "饮品", "瓶", new BigDecimal("100"), "PRODUCT", null, true, null, null,
                        List.of(), null)));

        BusinessException error = assertThrows(BusinessException.class, () -> service.onShelf(10L));

        assertEquals("PRODUCT_INVALID", error.getCode());
        assertEquals("实物商品必须关联仓库商品", error.getMessage());
        verify(productRepository, never()).update(any(Product.class));
    }

    /** 非实物商品（服务/加项）不需要仓库商品也能上架，并同步目录项为 ACTIVE。 */
    @Test
    void onShelfPublishesNonStockProductAndActivatesCatalogItem() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(existing(10L).withCatalogItemId(77L)));
        when(catalogItemRepository.findById(77L)).thenReturn(Optional.of(catalogItem(77L)));

        ProductView published = service.onShelf(10L);

        assertEquals("ON_SHELF", published.status());
        ArgumentCaptor<CatalogItem> captor = ArgumentCaptor.forClass(CatalogItem.class);
        verify(catalogItemRepository, org.mockito.Mockito.atLeastOnce()).update(captor.capture());
        CatalogItem synced = captor.getValue();
        assertEquals("ACTIVE", synced.status(), "上架后目录项必须 ACTIVE，否则点单列表看不到");
        assertEquals(10L, synced.productId());
    }

    /** 实物商品关联了 ACTIVE 仓库商品时上架成功。 */
    @Test
    void onShelfPublishesStockControlledProductWithActiveMaterial() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(existing(10L)
                .edited("可乐", "饮品", "瓶", new BigDecimal("100"), "PRODUCT", null, true, 55L, null,
                        List.of(), null)));
        when(materialRepository.findInStore(1L, 3L, 55L)).thenReturn(Optional.of(material(55L, "ACTIVE", null, null)));

        assertEquals("ON_SHELF", service.onShelf(10L).status());
    }

    /** 一行物料领域模型（跨子域窄读的夹具）。 */
    private static InventoryMaterial material(Long id, String status, List<String> images, String mainImage) {
        return new InventoryMaterial(id, 1L, 3L, "M-" + id, "物料" + id, "耗材", "件", null, BigDecimal.ZERO,
                null, "USD", images, mainImage, status, 0, LocalDateTime.now());
    }

    private static ProductApplicationService.ProductCommand command(List<String> imageUrls, String mainImageUrl) {
        return new ProductApplicationService.ProductCommand(3L, "P-1", "可乐", "饮品", "瓶", new BigDecimal("100"),
                null, false, 0, null, imageUrls, mainImageUrl);
    }

    /** 既有商品夹具（默认非实物、无物料、无目录项回指）。 */
    private static Product existing(Long id) {
        return new Product(id, 1L, 3L, "P-1", "可乐", "饮品", "PRODUCT", "瓶", new BigDecimal("100"), null, null,
                false, null, "DRAFT", 0, null, List.of(), null, 0);
    }

    private static CatalogItem catalogItem(Long id) {
        return new CatalogItem(id, null, 1L, 3L, "饮品", "PRODUCT", "可乐", "瓶", new BigDecimal("100"), null,
                List.of(), null, "INACTIVE", false, 0);
    }

    /** 端口返回值：聚合 + 审计快照（领域模型不承载审计列）。 */
    private static <T> Persisted<T> persisted(T aggregate, Long unusedId) {
        LocalDateTime now = LocalDateTime.of(2026, 9, 19, 20, 0, 0);
        return new Persisted<>(aggregate, new AuditSnapshot(null, now, null, now));
    }
}

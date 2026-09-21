package com.gvchat.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.infra.persistence.mapper.CatalogItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.InventoryMaterialMapper;
import com.gvchat.platform.order.infra.persistence.mapper.ProductMapper;
import com.gvchat.platform.order.infra.persistence.po.CatalogItemPo;
import com.gvchat.platform.order.infra.persistence.po.InventoryMaterialPo;
import com.gvchat.platform.order.infra.persistence.po.ProductPo;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 商品多图与主图规则单测：落库字段、主图归属、9 张上限。 */
class ProductApplicationServiceTest {

    private final ProductMapper productMapper = mock(ProductMapper.class);
    private final CatalogItemMapper catalogItemMapper = mock(CatalogItemMapper.class);
    private final InventoryMaterialMapper materialMapper = mock(InventoryMaterialMapper.class);
    private final ProductApplicationService service = new ProductApplicationService(productMapper, catalogItemMapper, materialMapper);

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    @Test
    void createPersistsImagesAndDefaultsMainImageToFirst() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        ProductPo created = service.create(command(List.of("/api/v1/media-public/b/saas/1/1.png", "/api/v1/media-public/b/saas/1/2.png"), null));

        assertEquals(List.of("/api/v1/media-public/b/saas/1/1.png", "/api/v1/media-public/b/saas/1/2.png"), created.getImageUrls());
        assertEquals("/api/v1/media-public/b/saas/1/1.png", created.getMainImageUrl());
        verify(productMapper).insert(created);
    }

    @Test
    void createTrimsAndDeduplicatesImages() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        ProductPo created = service.create(command(List.of(" /a.png ", "/a.png", "", "  "), null));

        assertEquals(List.of("/a.png"), created.getImageUrls());
        assertEquals("/a.png", created.getMainImageUrl());
    }

    @Test
    void createRejectsMainImageOutsideImages() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(command(List.of("/a.png"), "/b.png")));

        assertEquals("PRODUCT_INVALID", error.getCode());
        assertEquals("主图必须是已上传图片中的一张", error.getMessage());
        verifyNoInteractions(productMapper);
    }

    @Test
    void createRejectsMoreThanNineImages() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        List<String> tenImages = IntStream.rangeClosed(1, 10).mapToObj(index -> "/" + index + ".png").toList();

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(command(tenImages, null)));

        assertEquals("PRODUCT_INVALID", error.getCode());
        assertEquals("图片最多 9 张，当前 10 张", error.getMessage());
        verifyNoInteractions(productMapper);
    }

    @Test
    void createRejectsTooLongImageUrl() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(command(List.of("/" + "a".repeat(512) + ".png"), null)));

        assertEquals("PRODUCT_INVALID", error.getCode());
        verifyNoInteractions(productMapper);
    }

    @Test
    void updateReplacesImagesAndMainImage() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        ProductPo existing = new ProductPo(); existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L);
        when(productMapper.selectById(10L)).thenReturn(existing);

        ProductPo updated = service.update(10L,
                new ProductApplicationService.ProductCommand(null, null, "改名", null, null, null, null, null, null, null,
                        List.of("/x.png", "/y.png"), "/y.png"));

        assertEquals(List.of("/x.png", "/y.png"), updated.getImageUrls());
        assertEquals("/y.png", updated.getMainImageUrl());
        verify(productMapper, org.mockito.Mockito.atLeastOnce()).updateById(any(ProductPo.class));
    }

    @Test
    void updateClearsImagesWhenListIsEmpty() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        ProductPo existing = new ProductPo(); existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L);
        existing.setImageUrls(List.of("/old.png")); existing.setMainImageUrl("/old.png");
        when(productMapper.selectById(10L)).thenReturn(existing);

        ProductPo updated = service.update(10L,
                new ProductApplicationService.ProductCommand(null, null, null, null, null, null, null, null, null, null,
                        List.of(), null));

        assertEquals(List.of(), updated.getImageUrls());
        assertNull(updated.getMainImageUrl());
    }

    @Test
    void updateRejectsMainImageOutsideImages() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        ProductPo existing = new ProductPo(); existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L);
        when(productMapper.selectById(10L)).thenReturn(existing);

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(10L,
                new ProductApplicationService.ProductCommand(null, null, null, null, null, null, null, null, null, null,
                        List.of("/x.png"), "/y.png")));

        assertEquals("PRODUCT_INVALID", error.getCode());
        verify(productMapper, never()).updateById(any(ProductPo.class));
    }

    @Test
    void createWithoutImagesKeepsNullMainImage() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        ProductPo created = service.create(command(null, null));

        assertEquals(List.of(), created.getImageUrls());
        assertNull(created.getMainImageUrl());
    }

    /** 改商品图片后，目录项（点单列表的数据源）必须同步到新图。 */
    @Test
    void updateSyncsImagesToCatalogItem() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        ProductPo existing = new ProductPo();
        existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L); existing.setCatalogItemId(77L);
        when(productMapper.selectById(10L)).thenReturn(existing);
        CatalogItemPo catalog = new CatalogItemPo();
        catalog.setId(77L); catalog.setTenantId(1L); catalog.setStoreId(3L);
        when(catalogItemMapper.selectOne(any())).thenReturn(catalog);

        service.update(10L, new ProductApplicationService.ProductCommand(null, null, "可乐", null, null, null,
                null, null, null, null, List.of("/p-a.png", "/p-b.png"), "/p-b.png"));

        assertEquals(List.of("/p-a.png", "/p-b.png"), catalog.getImageUrls());
        assertEquals("/p-b.png", catalog.getMainImageUrl());
        verify(catalogItemMapper).updateById(catalog);
    }

    /** 新建商品时就带上图（目录项随商品创建），上架后点单页直接有缩略图。 */
    @Test
    void createSyncsImagesToNewCatalogItem() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        service.create(command(List.of("/a.png", "/b.png"), "/b.png"));

        ArgumentCaptor<CatalogItemPo> captor = ArgumentCaptor.forClass(CatalogItemPo.class);
        verify(catalogItemMapper).insert(captor.capture());
        assertEquals(List.of("/a.png", "/b.png"), captor.getValue().getImageUrls());
        assertEquals("/b.png", captor.getValue().getMainImageUrl());
    }

    /** 商品自己没有图时，目录项用它关联物料的图（物料加项场景图片只维护在物料上）。 */
    @Test
    void catalogImagesFallBackToLinkedMaterialWhenProductHasNoImages() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        ProductPo existing = new ProductPo();
        existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L);
        existing.setCatalogItemId(77L); existing.setMaterialId(55L);
        when(productMapper.selectById(10L)).thenReturn(existing);
        CatalogItemPo catalog = new CatalogItemPo();
        catalog.setId(77L); catalog.setTenantId(1L); catalog.setStoreId(3L);
        when(catalogItemMapper.selectOne(any())).thenReturn(catalog);
        InventoryMaterialPo material = new InventoryMaterialPo();
        material.setId(55L); material.setImageUrls(List.of("/m-a.png")); material.setMainImageUrl("/m-a.png");
        when(materialMapper.selectById(55L)).thenReturn(material);

        service.update(10L, new ProductApplicationService.ProductCommand(null, null, null, null, null, null,
                null, null, null, null, null, null));

        assertEquals(List.of("/m-a.png"), catalog.getImageUrls());
        assertEquals("/m-a.png", catalog.getMainImageUrl());
        verify(catalogItemMapper).updateById(catalog);
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
        verifyNoInteractions(productMapper);
    }

    /** 非实物商品（服务/加项）不需要关联仓库商品，默认也必须能建。 */
    @Test
    void createAllowsNonStockControlledProductWithoutMaterial() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        ProductPo created = service.create(command(null, null));

        assertEquals("DRAFT", created.getStatus());
        assertNull(created.getMaterialId());
    }

    @Test
    void createPersistsDescription() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        ProductPo created = service.create(new ProductApplicationService.ProductCommand(
                3L, "P-2", "可乐", "饮品", "瓶", new BigDecimal("100"), null, false, 0, "冰镇可口可乐 330ml", null, null));

        assertEquals("冰镇可口可乐 330ml", created.getDescription());
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
        verifyNoInteractions(productMapper);
    }

    @Test
    void updateClearsDescriptionWithBlankText() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        ProductPo existing = new ProductPo(); existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L);
        existing.setDescription("旧描述");
        when(productMapper.selectById(10L)).thenReturn(existing);

        ProductPo updated = service.update(10L, new ProductApplicationService.ProductCommand(
                null, null, null, null, null, null, null, null, null, "   ", null, null));

        assertNull(updated.getDescription());
    }

    /** 更新入口：商品已经是实物但没关联仓库商品时，任何更新都必须被挡下（且不落库）。 */
    @Test
    void updateRejectsStockControlledProductWithoutMaterial() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        ProductPo existing = new ProductPo(); existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L);
        existing.setStockControlled(true);
        when(productMapper.selectById(10L)).thenReturn(existing);

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(10L,
                new ProductApplicationService.ProductCommand(null, null, "改名", null, null, null, null, null, null, null, null, null)));

        assertEquals("PRODUCT_INVALID", error.getCode());
        assertEquals("实物商品必须关联仓库商品", error.getMessage());
        verify(productMapper, never()).updateById(any(ProductPo.class));
    }

    /** 更新入口：把非实物商品切换成实物商品时，没选仓库商品同样 400。 */
    @Test
    void updateRejectsSwitchingToStockControlledWithoutMaterial() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        ProductPo existing = new ProductPo(); existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L);
        existing.setStockControlled(false);
        when(productMapper.selectById(10L)).thenReturn(existing);

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(10L,
                new ProductApplicationService.ProductCommand(null, null, null, null, null, null, null, true, null, null, null, null)));

        assertEquals("PRODUCT_INVALID", error.getCode());
        assertEquals("实物商品必须关联仓库商品", error.getMessage());
    }

    /** 切换成实物商品并选了仓库商品时，开关与关联都必须落库。 */
    @Test
    void updatePersistsStockControlledSwitchWithMaterial() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        ProductPo existing = new ProductPo(); existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L);
        existing.setStockControlled(false);
        when(productMapper.selectById(10L)).thenReturn(existing);
        InventoryMaterialPo material = new InventoryMaterialPo();
        material.setId(55L); material.setStatus("ACTIVE");
        when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(material);

        ProductPo updated = service.update(10L, new ProductApplicationService.ProductCommand(
                null, null, null, null, null, null, 55L, true, null, null, null, null));

        assertEquals(Boolean.TRUE, updated.getStockControlled());
        assertEquals(55L, updated.getMaterialId());
    }

    /** 上架入口：实物商品没有关联仓库商品时必须是 400 PRODUCT_INVALID + 同一句消息。 */
    @Test
    void onShelfRejectsStockControlledProductWithoutMaterial() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        ProductPo existing = new ProductPo(); existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L);
        existing.setStockControlled(true);
        when(productMapper.selectById(10L)).thenReturn(existing);

        BusinessException error = assertThrows(BusinessException.class, () -> service.onShelf(10L));

        assertEquals("PRODUCT_INVALID", error.getCode());
        assertEquals("实物商品必须关联仓库商品", error.getMessage());
        verify(productMapper, never()).updateById(any(ProductPo.class));
    }

    /** 非实物商品（服务/加项）不需要仓库商品也能上架，并同步目录项为 ACTIVE。 */
    @Test
    void onShelfPublishesNonStockProductAndActivatesCatalogItem() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        ProductPo existing = new ProductPo(); existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L);
        existing.setStockControlled(false); existing.setCatalogItemId(77L); existing.setName("加钟");
        when(productMapper.selectById(10L)).thenReturn(existing);
        CatalogItemPo catalog = new CatalogItemPo();
        catalog.setId(77L); catalog.setTenantId(1L); catalog.setStoreId(3L);
        when(catalogItemMapper.selectOne(any())).thenReturn(catalog);

        ProductPo published = service.onShelf(10L);

        assertEquals("ON_SHELF", published.getStatus());
        assertEquals("ACTIVE", catalog.getStatus(), "上架后目录项必须 ACTIVE，否则点单列表看不到");
        assertEquals(10L, catalog.getProductId());
    }

    /** 实物商品关联了 ACTIVE 仓库商品时上架成功。 */
    @Test
    void onShelfPublishesStockControlledProductWithActiveMaterial() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        ProductPo existing = new ProductPo(); existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L);
        existing.setStockControlled(true); existing.setMaterialId(55L);
        when(productMapper.selectById(10L)).thenReturn(existing);
        InventoryMaterialPo material = new InventoryMaterialPo();
        material.setId(55L); material.setStatus("ACTIVE");
        when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(material);

        assertEquals("ON_SHELF", service.onShelf(10L).getStatus());
    }

    private static ProductApplicationService.ProductCommand command(List<String> imageUrls, String mainImageUrl) {
        return new ProductApplicationService.ProductCommand(3L, "P-1", "可乐", "饮品", "瓶", new BigDecimal("100"),
                null, false, 0, null, imageUrls, mainImageUrl);
    }
}

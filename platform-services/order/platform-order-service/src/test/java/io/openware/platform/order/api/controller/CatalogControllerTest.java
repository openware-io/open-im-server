package io.openware.platform.order.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.InventoryApplicationService;
import io.openware.platform.order.infra.persistence.mapper.CatalogItemMapper;
import io.openware.platform.order.infra.persistence.mapper.InventoryMaterialMapper;
import io.openware.platform.order.infra.persistence.mapper.ProductMapper;
import io.openware.platform.order.infra.persistence.po.CatalogItemPo;
import io.openware.platform.order.infra.persistence.po.InventoryMaterialPo;
import io.openware.platform.order.infra.persistence.po.ProductPo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 点单目录列表（B 端点单 / C 端自助加项共用）的四条规则：
 * 1) 只展示「关联了商品且商品已上架」的目录项，未关联商品的一律不出现；
 * 2) 列表响应必须带 imageUrls / mainImageUrl，目录项自身没图时按「关联商品 → 商品关联物料」兜底；
 * 3) 已上架但售罄的实物商品仍展示，只是 available=false + 原因；
 * 4) 是否受库存控制以商品为准（目录项 stock_controlled 只作缓存兜底），存量未回填也不能漏判售罄。
 * 库存可用量由 {@link InventoryApplicationService} 提供（批量 + TTL 缓存），此处按接口打桩。
 */
class CatalogControllerTest {

    private static final long TENANT_ID = 1L;
    private static final long STORE_ID = 3L;
    private static final long MATERIAL_ID = 55L;

    private final CatalogItemMapper catalogItemMapper = mock(CatalogItemMapper.class);
    private final ProductMapper productMapper = mock(ProductMapper.class);
    private final InventoryMaterialMapper materialMapper = mock(InventoryMaterialMapper.class);
    private final InventoryApplicationService inventoryService = mock(InventoryApplicationService.class);
    private final CatalogController controller =
            new CatalogController(catalogItemMapper, productMapper, materialMapper, inventoryService);

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void listKeepsCatalogItemOwnImages() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        CatalogItemPo item = catalogItem(10L, 50L);
        item.setImageUrls(List.of("/catalog.png"));
        item.setMainImageUrl("/catalog.png");
        givenItems(item);
        givenProducts(product(50L, null, "ON_SHELF", false));

        List<CatalogItemPo> result = controller.list(null, STORE_ID);

        assertEquals(1, result.size());
        assertEquals(List.of("/catalog.png"), result.getFirst().getImageUrls());
        assertEquals("/catalog.png", result.getFirst().getMainImageUrl());
        assertTrue(result.getFirst().getAvailable());
        verifyNoInteractions(materialMapper, inventoryService);
    }

    @Test
    void listFallsBackToProductImagesWhenCatalogItemHasNoImages() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        CatalogItemPo item = catalogItem(10L, 50L);
        givenItems(item);
        ProductPo product = product(50L, null, "ON_SHELF", false);
        product.setImageUrls(List.of("/p-a.png", "/p-b.png"));
        product.setMainImageUrl("/p-b.png");
        givenProducts(product);

        List<CatalogItemPo> result = controller.list(null, STORE_ID);

        assertEquals(List.of("/p-a.png", "/p-b.png"), result.getFirst().getImageUrls());
        assertEquals("/p-b.png", result.getFirst().getMainImageUrl());
    }

    @Test
    void listFallsBackToMaterialImagesWhenProductHasNoImages() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        CatalogItemPo item = catalogItem(10L, 50L);
        givenItems(item);
        givenProducts(product(50L, MATERIAL_ID, "ON_SHELF", false));
        InventoryMaterialPo material = new InventoryMaterialPo();
        material.setId(MATERIAL_ID);
        material.setImageUrls(List.of("/m-a.png"));
        material.setMainImageUrl("/m-a.png");
        when(materialMapper.selectById(MATERIAL_ID)).thenReturn(material);

        List<CatalogItemPo> result = controller.list(null, STORE_ID);

        assertEquals(List.of("/m-a.png"), result.getFirst().getImageUrls());
        assertEquals("/m-a.png", result.getFirst().getMainImageUrl());
    }

    /** 纯服务/加项目录项没有关联商品，不再出现在下单列表（历史种子数据同理）。 */
    @Test
    void listHidesCatalogItemWithoutProduct() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        givenItems(catalogItem(10L, null));

        assertEquals(List.of(), controller.list(null, STORE_ID));
        verifyNoInteractions(productMapper, materialMapper);
    }

    /** 目录项即使 status=ACTIVE，只要关联的商品没上架就不出现在下单列表。 */
    @Test
    void listHidesCatalogItemWhenProductIsNotOnShelf() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        givenItems(catalogItem(10L, 50L));
        givenProducts(product(50L, null, "DRAFT", false));

        assertEquals(List.of(), controller.list(null, STORE_ID));
    }

    @Test
    void listHidesCatalogItemWhenLinkedProductIsGone() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        givenItems(catalogItem(10L, 50L));
        when(productMapper.selectByIds(any())).thenReturn(List.of());

        assertEquals(List.of(), controller.list(null, STORE_ID));
    }

    /** 售罄不能消失：已上架 + 已关联仓库商品，但可用库存为 0 → 仍然展示且 available=false。 */
    @Test
    void listKeepsSoldOutProductAsUnavailable() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        CatalogItemPo item = catalogItem(10L, 50L);
        item.setStockControlled(true);
        givenItems(item);
        givenProducts(product(50L, MATERIAL_ID, "ON_SHELF", true));
        givenAvailableQty(BigDecimal.ZERO);

        List<CatalogItemPo> result = controller.list(null, STORE_ID);

        assertEquals(1, result.size());
        assertFalse(result.getFirst().getAvailable());
        assertEquals("已售罄", result.getFirst().getUnavailableReason());
    }

    @Test
    void listKeepsStockControlledProductAvailableWhenInStock() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        CatalogItemPo item = catalogItem(10L, 50L);
        item.setStockControlled(true);
        givenItems(item);
        givenProducts(product(50L, MATERIAL_ID, "ON_SHELF", true));
        givenAvailableQty(new BigDecimal("8"));

        List<CatalogItemPo> result = controller.list(null, STORE_ID);

        assertTrue(result.getFirst().getAvailable());
        assertNull(result.getFirst().getUnavailableReason());
        // 可用库存数量随响应下发：点单/加项页据此限制加号上限（提交前防超卖）
        assertEquals(0, new BigDecimal("8").compareTo(result.getFirst().getAvailableQuantity()));
        verify(inventoryService).availableQuantities(eq(TENANT_ID), eq(STORE_ID), any());
    }

    /** 不控制库存的商品：availableQuantity 为 null（前端按「不限量」处理，不显示库存）。 */
    @Test
    void listLeavesAvailableQuantityNullForNonStockControlledItem() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        givenItems(catalogItem(10L, 50L));
        givenProducts(product(50L, null, "ON_SHELF", false));

        List<CatalogItemPo> result = controller.list(null, STORE_ID);

        assertTrue(result.getFirst().getAvailable());
        assertNull(result.getFirst().getAvailableQuantity());
    }

    /**
     * F3 读取兜底：目录项缓存列是 0（V17 回填之前的历史数据），但商品是实物且库存 0，
     * 必须按商品口径判「已售罄」，并把权威标记回写到响应。
     */
    @Test
    void productStockFlagWinsOverStaleCatalogCacheColumn() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        CatalogItemPo item = catalogItem(10L, 50L);
        item.setStockControlled(false);
        givenItems(item);
        givenProducts(product(50L, MATERIAL_ID, "ON_SHELF", true));
        givenAvailableQty(BigDecimal.ZERO);

        List<CatalogItemPo> result = controller.list(null, STORE_ID);

        assertEquals(1, result.size());
        assertFalse(result.getFirst().getAvailable());
        assertEquals("已售罄", result.getFirst().getUnavailableReason());
        assertTrue(result.getFirst().getStockControlled());
        // 确实按商品的物料去查库存（55），而不是因为目录项缓存列是 0 就跳过库存判断
        ArgumentCaptor<List<Long>> materialIds = ArgumentCaptor.forClass(List.class);
        verify(inventoryService).availableQuantities(eq(TENANT_ID), eq(STORE_ID), materialIds.capture());
        assertEquals(List.of(MATERIAL_ID), materialIds.getValue());
    }

    /** 商品说不占库存时，即使目录项缓存列是 1 也保持可点（权威口径优先），且不查库存。 */
    @Test
    void productNonStockFlagWinsOverStaleCatalogCacheColumn() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        CatalogItemPo item = catalogItem(10L, 50L);
        item.setStockControlled(true);
        givenItems(item);
        givenProducts(product(50L, MATERIAL_ID, "ON_SHELF", false));

        List<CatalogItemPo> result = controller.list(null, STORE_ID);

        assertTrue(result.getFirst().getAvailable());
        assertFalse(result.getFirst().getStockControlled());
        verify(inventoryService, never()).availableQuantities(anyLong(), anyLong(), any());
    }

    /** 商品是实物但没关联物料：明确不可点 + 原因，不能当可点放过去。 */
    @Test
    void listMarksUnavailableWhenStockControlledProductHasNoMaterial() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        givenItems(catalogItem(10L, 50L));
        givenProducts(product(50L, null, "ON_SHELF", true));

        List<CatalogItemPo> result = controller.list(null, STORE_ID);

        assertFalse(result.getFirst().getAvailable());
        assertEquals("未关联物料", result.getFirst().getUnavailableReason());
        verify(inventoryService, never()).availableQuantities(anyLong(), anyLong(), any());
    }

    private void givenAvailableQty(BigDecimal available) {
        when(inventoryService.availableQuantities(eq(TENANT_ID), eq(STORE_ID), any()))
                .thenReturn(Map.of(MATERIAL_ID, available));
    }

    private void givenItems(CatalogItemPo... items) {
        when(catalogItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(items));
    }

    private void givenProducts(ProductPo... products) {
        when(productMapper.selectByIds(any())).thenReturn(List.of(products));
    }

    private static CatalogItemPo catalogItem(Long id, Long productId) {
        CatalogItemPo item = new CatalogItemPo();
        item.setId(id);
        item.setTenantId(TENANT_ID);
        item.setStoreId(STORE_ID);
        item.setProductId(productId);
        item.setCategory("酒水");
        item.setItemType("PRODUCT");
        item.setName("百威啤酒");
        item.setUnit("瓶");
        item.setUnitPrice(new BigDecimal("1500"));
        item.setStatus("ACTIVE");
        item.setStockControlled(false);
        item.setSortOrder(1);
        item.setImageUrls(List.of());
        return item;
    }

    private static ProductPo product(Long id, Long materialId, String status, boolean stockControlled) {
        ProductPo product = new ProductPo();
        product.setId(id);
        product.setTenantId(TENANT_ID);
        product.setStoreId(STORE_ID);
        product.setMaterialId(materialId);
        product.setStatus(status);
        product.setStockControlled(stockControlled);
        return product;
    }
}

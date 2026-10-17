package io.openware.platform.order.api.controller;

import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.dto.CatalogItemView;
import io.openware.platform.order.application.CatalogApplicationService;
import io.openware.platform.order.application.InventoryApplicationService;
import io.openware.platform.order.domain.catalog.model.CatalogItem;
import io.openware.platform.order.domain.catalog.model.Product;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.domain.inventory.model.InventoryMaterial;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * 1) 只展示「关联了商品且商品已上架」的目录项，未关联商品的一律不出现（服务类除外）；
 * 2) 列表响应必须带 imageUrls / mainImageUrl，目录项自身没图时按「关联商品 → 商品关联物料」兜底；
 * 3) 已上架但售罄的实物商品仍展示，只是 available=false + 原因；
 * 4) 是否受库存控制以商品为准（目录项 stock_controlled 只作缓存兜底），存量未回填也不能漏判售罄。
 * 库存可用量由 {@link InventoryApplicationService} 提供（批量 + TTL 缓存），此处按接口打桩。
 *
 * <p><b>迁移后的打桩口径</b>：目录项与商品不再是 PO + Mapper，而是领域端口
 * （{@code CatalogItemRepository} / {@code ProductRepository}）+ 不可变记录，
 * 因此用例里的夹具是 record 构造而不是 setter；断言的对象是
 * {@link CatalogItemView}（JSON 字段名与原 PO 一致）。
 */
class CatalogControllerTest {

    private static final long TENANT_ID = 1L;
    private static final long STORE_ID = 3L;
    private static final long MATERIAL_ID = 55L;

    private final CatalogItemRepository catalogItemRepository = mock(CatalogItemRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final InventoryMaterialRepository materialRepository = mock(InventoryMaterialRepository.class);
    private final InventoryApplicationService inventoryService = mock(InventoryApplicationService.class);
    private final CatalogApplicationService catalogService = new CatalogApplicationService(
            catalogItemRepository, productRepository, materialRepository, inventoryService);
    private final CatalogController controller = new CatalogController(catalogService);

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void listKeepsCatalogItemOwnImages() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        givenItems(catalogItem(10L, 50L, true, List.of("/catalog.png"), "/catalog.png"));
        givenProducts(product(50L, null, "ON_SHELF", false, List.of(), null));

        List<CatalogItemView> result = controller.list(null, STORE_ID);

        assertEquals(1, result.size());
        assertEquals(List.of("/catalog.png"), result.getFirst().imageUrls());
        assertEquals("/catalog.png", result.getFirst().mainImageUrl());
        assertTrue(result.getFirst().available());
        verifyNoInteractions(materialRepository, inventoryService);
    }

    @Test
    void listFallsBackToProductImagesWhenCatalogItemHasNoImages() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        givenItems(catalogItem(10L, 50L, true, List.of(), null));
        givenProducts(product(50L, null, "ON_SHELF", false, List.of("/p-a.png", "/p-b.png"), "/p-b.png"));

        List<CatalogItemView> result = controller.list(null, STORE_ID);

        assertEquals(List.of("/p-a.png", "/p-b.png"), result.getFirst().imageUrls());
        assertEquals("/p-b.png", result.getFirst().mainImageUrl());
    }

    @Test
    void listFallsBackToMaterialImagesWhenProductHasNoImages() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        givenItems(catalogItem(10L, 50L, true, List.of(), null));
        givenProducts(product(50L, MATERIAL_ID, "ON_SHELF", false, List.of(), null));
        when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(
                new InventoryMaterial(MATERIAL_ID, TENANT_ID, STORE_ID, "M-55", "物料", "耗材", "件", null,
                        BigDecimal.ZERO, null, "USD", List.of("/m-a.png"), "/m-a.png", "ACTIVE", 0, null)));

        List<CatalogItemView> result = controller.list(null, STORE_ID);

        assertEquals(List.of("/m-a.png"), result.getFirst().imageUrls());
        assertEquals("/m-a.png", result.getFirst().mainImageUrl());
    }

    /** 纯服务/加项目录项没有关联商品，不再出现在下单列表（历史种子数据同理）。 */
    @Test
    void listHidesCatalogItemWithoutProduct() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        givenItems(catalogItem(10L, null, true, List.of(), null));

        assertEquals(List.of(), controller.list(null, STORE_ID));
        verifyNoInteractions(productRepository, materialRepository);
    }

    /** 目录项即使 status=ACTIVE，只要关联的商品没上架就不出现在下单列表。 */
    @Test
    void listHidesCatalogItemWhenProductIsNotOnShelf() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        givenItems(catalogItem(10L, 50L, true, List.of(), null));
        givenProducts(product(50L, null, "DRAFT", false, List.of(), null));

        assertEquals(List.of(), controller.list(null, STORE_ID));
    }

    @Test
    void listHidesCatalogItemWhenLinkedProductIsGone() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        givenItems(catalogItem(10L, 50L, true, List.of(), null));
        when(productRepository.findById(any())).thenReturn(Optional.empty());

        assertEquals(List.of(), controller.list(null, STORE_ID));
    }

    /** 售罄不能消失：已上架 + 已关联仓库商品，但可用库存为 0 → 仍然展示且 available=false。 */
    @Test
    void listKeepsSoldOutProductAsUnavailable() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        givenItems(catalogItem(10L, 50L, true, List.of(), null));
        givenProducts(product(50L, MATERIAL_ID, "ON_SHELF", true, List.of(), null));
        givenAvailableQty(BigDecimal.ZERO);

        List<CatalogItemView> result = controller.list(null, STORE_ID);

        assertEquals(1, result.size());
        assertFalse(result.getFirst().available());
        assertEquals("已售罄", result.getFirst().unavailableReason());
    }

    @Test
    void listKeepsStockControlledProductAvailableWhenInStock() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        givenItems(catalogItem(10L, 50L, true, List.of(), null));
        givenProducts(product(50L, MATERIAL_ID, "ON_SHELF", true, List.of(), null));
        givenAvailableQty(new BigDecimal("8"));

        List<CatalogItemView> result = controller.list(null, STORE_ID);

        assertTrue(result.getFirst().available());
        assertNull(result.getFirst().unavailableReason());
        // 可用库存数量随响应下发：点单/加项页据此限制加号上限（提交前防超卖）
        assertEquals(0, new BigDecimal("8").compareTo(result.getFirst().availableQuantity()));
        verify(inventoryService).availableQuantities(eq(TENANT_ID), eq(STORE_ID), any());
    }

    /** 不控制库存的商品：availableQuantity 为 null（前端按「不限量」处理，不显示库存）。 */
    @Test
    void listLeavesAvailableQuantityNullForNonStockControlledItem() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        givenItems(catalogItem(10L, 50L, true, List.of(), null));
        givenProducts(product(50L, null, "ON_SHELF", false, List.of(), null));

        List<CatalogItemView> result = controller.list(null, STORE_ID);

        assertTrue(result.getFirst().available());
        assertNull(result.getFirst().availableQuantity());
    }

    /**
     * F3 读取兜底：目录项缓存列是 0（V17 回填之前的历史数据），但商品是实物且库存 0，
     * 必须按商品口径判「已售罄」，并把权威标记回写到响应。
     */
    @Test
    void productStockFlagWinsOverStaleCatalogCacheColumn() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        givenItems(catalogItem(10L, 50L, false, List.of(), null));
        givenProducts(product(50L, MATERIAL_ID, "ON_SHELF", true, List.of(), null));
        givenAvailableQty(BigDecimal.ZERO);

        List<CatalogItemView> result = controller.list(null, STORE_ID);

        assertEquals(1, result.size());
        assertFalse(result.getFirst().available());
        assertEquals("已售罄", result.getFirst().unavailableReason());
        assertTrue(result.getFirst().stockControlled());
        // 确实按商品的物料去查库存（55），而不是因为目录项缓存列是 0 就跳过库存判断
        ArgumentCaptor<List<Long>> materialIds = ArgumentCaptor.forClass(List.class);
        verify(inventoryService).availableQuantities(eq(TENANT_ID), eq(STORE_ID), materialIds.capture());
        assertEquals(List.of(MATERIAL_ID), materialIds.getValue());
    }

    /** 商品说不占库存时，即使目录项缓存列是 1 也保持可点（权威口径优先），且不查库存。 */
    @Test
    void productNonStockFlagWinsOverStaleCatalogCacheColumn() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        givenItems(catalogItem(10L, 50L, true, List.of(), null));
        givenProducts(product(50L, MATERIAL_ID, "ON_SHELF", false, List.of(), null));

        List<CatalogItemView> result = controller.list(null, STORE_ID);

        assertTrue(result.getFirst().available());
        assertFalse(result.getFirst().stockControlled());
        verify(inventoryService, never()).availableQuantities(anyLong(), anyLong(), any());
    }

    /** 商品是实物但没关联物料：明确不可点 + 原因，不能当可点放过去。 */
    @Test
    void listMarksUnavailableWhenStockControlledProductHasNoMaterial() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        givenItems(catalogItem(10L, 50L, true, List.of(), null));
        givenProducts(product(50L, null, "ON_SHELF", true, List.of(), null));

        List<CatalogItemView> result = controller.list(null, STORE_ID);

        assertFalse(result.getFirst().available());
        assertEquals("未关联物料", result.getFirst().unavailableReason());
        verify(inventoryService, never()).availableQuantities(anyLong(), anyLong(), any());
    }

    private void givenAvailableQty(BigDecimal available) {
        when(inventoryService.availableQuantities(eq(TENANT_ID), eq(STORE_ID), any()))
                .thenReturn(Map.of(MATERIAL_ID, available));
    }

    private void givenItems(CatalogItem... items) {
        when(catalogItemRepository.find(any(CatalogItemRepository.CatalogItemQuery.class)))
                .thenReturn(List.of(items));
    }

    private void givenProducts(Product... products) {
        for (Product product : products) {
            when(productRepository.findById(product.id())).thenReturn(Optional.of(product));
        }
    }

    private static CatalogItem catalogItem(Long id, Long productId, boolean stockControlled, List<String> images,
                                          String mainImage) {
        return new CatalogItem(id, productId, TENANT_ID, STORE_ID, "酒水", "PRODUCT", "百威啤酒", "瓶",
                new BigDecimal("1500"), null, images, mainImage, "ACTIVE", stockControlled, 1);
    }

    private static Product product(Long id, Long materialId, String status, boolean stockControlled,
                                   List<String> images, String mainImage) {
        return new Product(id, TENANT_ID, STORE_ID, "P-" + id, "百威啤酒", "酒水", "PRODUCT", "瓶",
                new BigDecimal("1500"), materialId, null, stockControlled, null, status, 0, null, images,
                mainImage, 0);
    }
}

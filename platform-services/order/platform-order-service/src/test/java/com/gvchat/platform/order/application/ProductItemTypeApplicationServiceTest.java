package com.gvchat.platform.order.application;

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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.infra.client.ResourceStateClient;
import com.gvchat.platform.order.infra.client.ResourceStateClient.ServerSnapshot;
import com.gvchat.platform.order.infra.persistence.mapper.CatalogItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.InventoryMaterialMapper;
import com.gvchat.platform.order.infra.persistence.mapper.ProductMapper;
import com.gvchat.platform.order.infra.persistence.po.CatalogItemPo;
import com.gvchat.platform.order.infra.persistence.po.InventoryMaterialPo;
import com.gvchat.platform.order.infra.persistence.po.ProductPo;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 商品分「实物 / 服务」的口径单测（第 3 点）：
 *  - 服务型商品必须关联「存在 + KTV_SERVER + 同门店 + 启用中 + 未被占用」的服务人员；
 *  - 实物商品忽略服务人员字段（不做任何资源域调用，落库为 NULL）；
 *  - 目录项 itemType / stock_controlled 必须跟着商品回写（加项与点单共用同一份 SERVICE 目录项）；
 *  - 服务人员名称是展示用的降级读：资源服务不可达时名称 null、列表照常返回。
 */
class ProductItemTypeApplicationServiceTest {

    private static final long TENANT_ID = 1L;
    private static final long STORE_ID = 3L;

    private final ProductMapper productMapper = mock(ProductMapper.class);
    private final CatalogItemMapper catalogItemMapper = mock(CatalogItemMapper.class);
    private final InventoryMaterialMapper materialMapper = mock(InventoryMaterialMapper.class);
    private final ResourceStateClient resourceStateClient = mock(ResourceStateClient.class);
    private final ProductApplicationService service = new ProductApplicationService(
            productMapper, catalogItemMapper, materialMapper, AuditClient.disabled(), resourceStateClient);

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
        verify(productMapper, never()).insert(any(ProductPo.class));
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
        ProductPo occupied = new ProductPo();
        occupied.setId(88L);
        occupied.setName("已有服务商品");
        when(productMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(occupied);

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

        ProductPo created = service.create(serviceCommand(9L));

        assertEquals("SERVICE", created.getItemType());
        assertEquals(9L, created.getServerResourceId());
        assertEquals("小美", created.getServerResourceName());
        assertEquals(Boolean.FALSE, created.getStockControlled(), "服务不占库存");
        assertNull(created.getMaterialId(), "服务不需要仓库商品");

        ArgumentCaptor<CatalogItemPo> captor = ArgumentCaptor.forClass(CatalogItemPo.class);
        verify(catalogItemMapper).insert(captor.capture());
        CatalogItemPo catalog = captor.getValue();
        assertEquals("SERVICE", catalog.getItemType(), "目录项类型必须与商品一致（加项与服务人员点单共用同一份）");
        assertEquals(Boolean.FALSE, catalog.getStockControlled());
        assertEquals(new BigDecimal("100"), catalog.getUnitPrice(), "服务目录项单价沿用商品售价");
    }

    // —— 实物商品：忽略服务人员字段 ————————————————————————————————

    @Test
    void createPhysicalProductIgnoresServerResourceWithoutCallingResourceService() {
        tenant();
        when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(activeMaterial(55L));

        ProductPo created = service.create(new ProductApplicationService.ProductCommand(STORE_ID, "P-1", "可乐", "饮品",
                "瓶", new BigDecimal("100"), 55L, true, 0, null, null, null, "PRODUCT", 9L));

        assertEquals("PRODUCT", created.getItemType());
        assertNull(created.getServerResourceId(), "实物商品必须忽略服务人员字段");
        verifyNoInteractions(resourceStateClient);
    }

    /** 不传 itemType 的既有调用方（老前端/老单测）继续按实物商品处理。 */
    @Test
    void createWithoutItemTypeDefaultsToPhysicalProduct() {
        tenant();

        ProductPo created = service.create(new ProductApplicationService.ProductCommand(STORE_ID, "P-2", "加钟", "其他",
                "次", new BigDecimal("100"), null, false, 0, null, null, null));

        assertEquals("PRODUCT", created.getItemType());
        verifyNoInteractions(resourceStateClient);
    }

    // —— 编辑态：类型切换 ————————————————————————————————————————

    @Test
    void updateSwitchingToServiceRequiresServer() {
        tenant();
        ProductPo existing = physicalProduct(10L);
        when(productMapper.selectById(10L)).thenReturn(existing);

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(10L,
                new ProductApplicationService.ProductCommand(null, null, null, null, null, null, null, null, null,
                        null, null, null, "SERVICE", null)));

        assertEquals("PRODUCT_SERVICE_SERVER_REQUIRED", error.getCode());
    }

    /** 服务 → 实物：清空服务人员（释放唯一键），库存/物料校验回到原口径。 */
    @Test
    void updateSwitchingBackToPhysicalClearsServerAndMaterialRulesApply() {
        tenant();
        ProductPo existing = physicalProduct(10L);
        existing.setItemType("SERVICE");
        existing.setServerResourceId(5L);
        existing.setStockControlled(false);
        existing.setCatalogItemId(77L);
        when(productMapper.selectById(10L)).thenReturn(existing);
        CatalogItemPo catalog = new CatalogItemPo();
        catalog.setId(77L);
        catalog.setTenantId(TENANT_ID);
        catalog.setStoreId(STORE_ID);
        when(catalogItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(catalog);
        when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(activeMaterial(55L));

        ProductPo updated = service.update(10L, new ProductApplicationService.ProductCommand(
                null, null, null, null, null, null, 55L, true, null, null, null, null, "PRODUCT", 5L));

        assertEquals("PRODUCT", updated.getItemType());
        assertNull(updated.getServerResourceId(), "切回实物必须清空服务人员");
        assertEquals(55L, updated.getMaterialId());
        assertEquals("PRODUCT", catalog.getItemType(), "目录项类型必须跟着切回 PRODUCT");
        verifyNoInteractions(resourceStateClient);
    }

    /** 服务 → 服务（改名/改价，命令不带 serverResourceId）：沿用既有服务人员，不重复校验占用自己。 */
    @Test
    void updateServiceProductKeepsExistingServer() {
        tenant();
        ProductPo existing = physicalProduct(10L);
        existing.setItemType("SERVICE");
        existing.setServerResourceId(5L);
        existing.setStockControlled(false);
        when(productMapper.selectById(10L)).thenReturn(existing);
        givenServer(5L, "KTV_SERVER", STORE_ID, "ENABLED");

        ProductPo updated = service.update(10L, new ProductApplicationService.ProductCommand(
                null, null, "新名字", null, null, null, null, null, null, null, null, null, null, null));

        assertEquals(5L, updated.getServerResourceId());
        assertEquals("新名字", updated.getName());
    }

    @Test
    void onShelfRejectsServiceProductWithoutServer() {
        tenant();
        ProductPo existing = physicalProduct(10L);
        existing.setItemType("SERVICE");
        existing.setStockControlled(false);
        when(productMapper.selectById(10L)).thenReturn(existing);

        BusinessException error = assertThrows(BusinessException.class, () -> service.onShelf(10L));

        assertEquals("PRODUCT_SERVICE_SERVER_REQUIRED", error.getCode());
    }

    // —— 列表：服务人员名称（读路径降级） ————————————————————————————

    @Test
    void listFillsServerNameForServiceProducts() {
        tenant();
        ProductPo product = physicalProduct(10L);
        product.setItemType("SERVICE");
        product.setServerResourceId(5L);
        when(productMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(product));
        when(resourceStateClient.resources(eq("KTV_SERVER"), eq(STORE_ID)))
                .thenReturn(List.of(new ServerSnapshot(5L, "小美", "S01", "KTV_SERVER", STORE_ID, "ENABLED")));

        List<ProductPo> rows = service.list(STORE_ID, null, null);

        assertEquals("小美", rows.get(0).getServerResourceName());
    }

    /** 资源服务不可达：名称降级为 null，列表照常返回（绝不 5xx）。 */
    @Test
    void listDegradesServerNameWhenResourceServiceUnavailable() {
        tenant();
        ProductPo product = physicalProduct(10L);
        product.setItemType("SERVICE");
        product.setServerResourceId(5L);
        when(productMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(product));
        when(resourceStateClient.resources(anyString(), any())).thenThrow(new IllegalStateException("resource down"));

        List<ProductPo> rows = service.list(STORE_ID, null, null);

        assertEquals(1, rows.size());
        assertNull(rows.get(0).getServerResourceName());
        assertEquals(5L, rows.get(0).getServerResourceId(), "ID 仍在，前端可显示「服务人员 #5」");
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

    private static ProductPo physicalProduct(Long id) {
        ProductPo product = new ProductPo();
        product.setId(id);
        product.setTenantId(TENANT_ID);
        product.setStoreId(STORE_ID);
        product.setItemType("PRODUCT");
        product.setName("可乐");
        product.setStockControlled(true);
        return product;
    }

    private static InventoryMaterialPo activeMaterial(Long id) {
        InventoryMaterialPo material = new InventoryMaterialPo();
        material.setId(id);
        material.setStatus("ACTIVE");
        return material;
    }
}

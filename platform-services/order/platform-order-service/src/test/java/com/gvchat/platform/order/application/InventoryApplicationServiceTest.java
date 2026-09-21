package com.gvchat.platform.order.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.infra.persistence.mapper.CatalogItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.InventoryMaterialMapper;
import com.gvchat.platform.order.infra.persistence.mapper.InventoryStockMapper;
import com.gvchat.platform.order.infra.persistence.mapper.InventoryTransactionMapper;
import com.gvchat.platform.order.infra.persistence.mapper.ProductMapper;
import com.gvchat.platform.order.infra.persistence.po.CatalogItemPo;
import com.gvchat.platform.order.infra.persistence.po.InventoryMaterialPo;
import com.gvchat.platform.order.infra.persistence.po.InventoryStockPo;
import com.gvchat.platform.order.infra.persistence.po.InventoryTransactionPo;
import com.gvchat.platform.order.infra.persistence.po.ProductPo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class InventoryApplicationServiceTest {
    private final InventoryMaterialMapper materialMapper = mock(InventoryMaterialMapper.class);
    private final InventoryStockMapper stockMapper = mock(InventoryStockMapper.class);
    private final InventoryTransactionMapper transactionMapper = mock(InventoryTransactionMapper.class);
    private final ProductMapper productMapper = mock(ProductMapper.class);
    private final CatalogItemMapper catalogItemMapper = mock(CatalogItemMapper.class);
    private final InventoryApplicationService service = new InventoryApplicationService(
            materialMapper, stockMapper, transactionMapper, productMapper, catalogItemMapper);

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    /**
     * MyBatis-Plus 的列名解析是懒求值的：纯单元测试（无 Spring/MyBatis 上下文）里必须先注册实体的 TableInfo，
     * 生产代码里的 {@code LambdaUpdateWrapper.set(Po::getXxx, ...)} 才能把方法引用解析成列名。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InventoryMaterialPo.class);
    }

    @Test
    void consumeRejectsWhenAvailableStockIsInsufficient() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        InventoryMaterialPo material = new InventoryMaterialPo(); material.setId(10L); material.setTenantId(1L); material.setStoreId(3L); material.setStatus("ACTIVE");
        InventoryStockPo stock = new InventoryStockPo(); stock.setId(20L); stock.setOnHandQty(new BigDecimal("2")); stock.setReservedQty(new BigDecimal("1")); stock.setVersion(0);
        when(transactionMapper.findByIdempotency(1L, "k1")).thenReturn(null);
        when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(material);
        when(stockMapper.selectForUpdate(1L, 3L, 10L)).thenReturn(stock);
        assertThrows(RuntimeException.class, () -> service.changeStock(10L, new BigDecimal("2"), "CONSUME", "ORDER_ITEM", "1", "test", "k1"));
        verify(transactionMapper, never()).insert(any(InventoryTransactionPo.class));
    }

    @Test
    void repeatedIdempotencyKeyReturnsOriginalTransaction() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        InventoryTransactionPo existing = new InventoryTransactionPo(); existing.setId(99L);
        when(transactionMapper.findByIdempotency(1L, "same")).thenReturn(existing);
        assertEquals(existing, service.changeStock(10L, BigDecimal.ONE, "RECEIPT", "RECEIPT", null, "test", "same"));
        verifyNoInteractions(materialMapper, stockMapper);
    }

    @Test
    void createMaterialPersistsImagesAndExplicitMainImage() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        InventoryMaterialPo created = service.createMaterial(new InventoryApplicationService.MaterialCommand(
                3L, "M-1", "纸巾", "耗材", "包", BigDecimal.ONE, null, "客用抽纸，两包装",
                List.of("/api/v1/media-public/b/saas/1/a.png", "/api/v1/media-public/b/saas/1/b.png"), "/api/v1/media-public/b/saas/1/b.png"));

        assertEquals(List.of("/api/v1/media-public/b/saas/1/a.png", "/api/v1/media-public/b/saas/1/b.png"), created.getImageUrls());
        assertEquals("/api/v1/media-public/b/saas/1/b.png", created.getMainImageUrl());
        assertEquals("客用抽纸，两包装", created.getDescription());
    }

    @Test
    void createMaterialWithoutDescriptionKeepsNull() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        InventoryMaterialPo created = service.createMaterial(new InventoryApplicationService.MaterialCommand(
                3L, "M-1", "纸巾", "耗材", "包", BigDecimal.ONE, null, null, null, null));

        assertNull(created.getDescription());
    }

    /** 描述超长必须 400（MATERIAL_INVALID）并给出明确中文消息，不能落库后被数据库截断。 */
    @Test
    void createMaterialRejectsTooLongDescription() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createMaterial(new InventoryApplicationService.MaterialCommand(
                        3L, "M-1", "纸巾", "耗材", "包", BigDecimal.ONE, null, "描".repeat(256), null, null)));

        assertEquals("MATERIAL_INVALID", error.getCode());
        assertEquals("描述长度不能超过 255 个字符", error.getMessage());
        verify(materialMapper, never()).insert(any(InventoryMaterialPo.class));
    }

    @Test
    void createMaterialRejectsMainImageOutsideImages() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createMaterial(new InventoryApplicationService.MaterialCommand(
                        3L, "M-1", "纸巾", "耗材", "包", BigDecimal.ONE, null, null, List.of("/a.png"), "/b.png")));

        assertEquals("MATERIAL_INVALID", error.getCode());
        assertEquals("主图必须是已上传图片中的一张", error.getMessage());
        verify(materialMapper, never()).insert(any(InventoryMaterialPo.class));
    }

    @Test
    void createMaterialRejectsMoreThanNineImages() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        List<String> tenImages = java.util.stream.IntStream.rangeClosed(1, 10).mapToObj(index -> "/" + index + ".png").toList();

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createMaterial(new InventoryApplicationService.MaterialCommand(
                        3L, "M-1", "纸巾", "耗材", "包", BigDecimal.ONE, null, null, tenImages, null)));

        assertEquals("MATERIAL_INVALID", error.getCode());
        verify(materialMapper, never()).insert(any(InventoryMaterialPo.class));
    }

    @Test
    void updateMaterialReplacesImagesAndDefaultsMainImageToFirst() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        InventoryMaterialPo existing = new InventoryMaterialPo();
        existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L); existing.setName("旧名");
        when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        InventoryMaterialPo updated = service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                null, null, "新名", null, null, null, null, null, List.of("/a.png", "/b.png"), null));

        assertEquals("新名", updated.getName());
        assertEquals(List.of("/a.png", "/b.png"), updated.getImageUrls());
        assertEquals("/a.png", updated.getMainImageUrl());
        LambdaUpdateWrapper<InventoryMaterialPo> update = capturedUpdate();
        assertTrue(update.getSqlSet().contains("name"), update.getSqlSet());
        assertTrue(update.getSqlSet().contains("image_urls"), update.getSqlSet());
        assertTrue(update.getSqlSet().contains("main_image_url"), update.getSqlSet());
    }

    @Test
    void updateMaterialReplacesDescription() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        InventoryMaterialPo existing = new InventoryMaterialPo();
        existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L); existing.setDescription("旧描述");
        when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        InventoryMaterialPo updated = service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                null, null, null, null, null, null, null, "新描述", null, null));

        assertEquals("新描述", updated.getDescription());
        LambdaUpdateWrapper<InventoryMaterialPo> update = capturedUpdate();
        assertTrue(update.getParamNameValuePairs().values().contains("新描述"), update.getSqlSet());
    }

    /**
     * 运营把描述清空时前端传空串，应归一为 null 落库，而不是存一个空白串。
     * 回归：必须让 {@code description} 以 NULL 进 SET —— {@code updateById(po)} 会被 MyBatis-Plus 的
     * {@code FieldStrategy.NOT_NULL} 整列跳过，表现为「响应已清空、库里还在」。
     */
    @Test
    void updateMaterialClearsDescriptionWithBlankText() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        InventoryMaterialPo existing = new InventoryMaterialPo();
        existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L); existing.setDescription("旧描述");
        when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        InventoryMaterialPo updated = service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                null, null, null, null, null, null, null, "   ", null, null));

        assertNull(updated.getDescription());
        LambdaUpdateWrapper<InventoryMaterialPo> update = capturedUpdate();
        assertTrue(update.getSqlSet().contains("description"), update.getSqlSet());
        assertTrue(update.getParamNameValuePairs().values().contains(null),
                "清空描述必须把 NULL 作为绑定参数下发（updateById 做不到这一点）");
    }

    /** 取回实际下发的 UPDATE 包装器：断言本次要写的列与绑定参数。 */
    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<InventoryMaterialPo> capturedUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<InventoryMaterialPo>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(materialMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    @Test
    void updateMaterialRejectsTooLongDescription() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        InventoryMaterialPo existing = new InventoryMaterialPo();
        existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L);
        when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                        null, null, null, null, null, null, null, "描".repeat(256), null, null)));

        assertEquals("MATERIAL_INVALID", error.getCode());
        assertEquals("描述长度不能超过 255 个字符", error.getMessage());
        verify(materialMapper, never()).update(isNull(), any());
    }

    @Test
    void updateMaterialRejectsMainImageOutsideImages() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        InventoryMaterialPo existing = new InventoryMaterialPo();
        existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L); existing.setName("物料");
        when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                        null, null, null, null, null, null, null, null, List.of("/a.png"), "/zzz.png")));

        assertEquals("MATERIAL_INVALID", error.getCode());
        verify(materialMapper, never()).update(isNull(), any());
    }

    @Test
    void updateMaterialRejectsMissingMaterial() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                        null, null, "x", null, null, null, null, null, null, null)));

        assertEquals("MATERIAL_NOT_FOUND", error.getCode());
    }

    /** 物料换图后，镜像该物料的点单目录项要跟着有图（否则点单页还是旧图/无图）。 */
    @Test
    void updateMaterialImagesSyncToLinkedCatalogItem() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        InventoryMaterialPo existing = new InventoryMaterialPo();
        existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L); existing.setName("物料");
        existing.setImageUrls(List.of("/old.png")); existing.setMainImageUrl("/old.png");
        when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        ProductPo product = new ProductPo();
        product.setId(50L); product.setTenantId(1L); product.setStoreId(3L);
        product.setMaterialId(10L); product.setCatalogItemId(70L);
        when(productMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(product));
        CatalogItemPo catalog = new CatalogItemPo();
        catalog.setId(70L); catalog.setTenantId(1L); catalog.setStoreId(3L);
        when(catalogItemMapper.selectById(70L)).thenReturn(catalog);

        service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                null, null, null, null, null, null, null, null, List.of("/new-a.png", "/new-b.png"), "/new-b.png"));

        assertEquals(List.of("/new-a.png", "/new-b.png"), catalog.getImageUrls());
        assertEquals("/new-b.png", catalog.getMainImageUrl());
        verify(catalogItemMapper).updateById(catalog);
    }

    /** 商品自己有图时目录项以商品图为准，物料换图不能把商品图冲掉。 */
    @Test
    void updateMaterialImagesDoNotOverwriteCatalogItemBackedByProductImages() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        InventoryMaterialPo existing = new InventoryMaterialPo();
        existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L);
        when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        ProductPo product = new ProductPo();
        product.setId(50L); product.setTenantId(1L); product.setStoreId(3L);
        product.setMaterialId(10L); product.setCatalogItemId(70L);
        product.setImageUrls(List.of("/product.png")); product.setMainImageUrl("/product.png");
        when(productMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(product));

        service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                null, null, null, null, null, null, null, null, List.of("/material.png"), null));

        verify(catalogItemMapper, never()).updateById(any(CatalogItemPo.class));
    }

    // ---- 采购价（V20 ord_inventory_material.purchase_price，最小货币单位：分）----

    /** 新建带采购价：原样落库（分），不做任何单位换算。 */
    @Test
    void createMaterialPersistsPurchasePrice() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        InventoryMaterialPo created = service.createMaterial(new InventoryApplicationService.MaterialCommand(
                3L, "M-1", "可乐", "饮品", "瓶", BigDecimal.ZERO, new BigDecimal("350"), null, null, null));

        assertEquals(0, created.getPurchasePrice().compareTo(new BigDecimal("350")));
        verify(materialMapper).insert(created);
    }

    /** 采购价可空：不传即「未维护」，落库 null。 */
    @Test
    void createMaterialWithoutPurchasePriceKeepsNull() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        InventoryMaterialPo created = service.createMaterial(new InventoryApplicationService.MaterialCommand(
                3L, "M-1", "可乐", "饮品", "瓶", BigDecimal.ZERO, null, null, null, null));

        assertNull(created.getPurchasePrice());
    }

    /** 0 = 清空/未填：落库 null，不写 0 分（避免「0 分」与「未维护」两种含义混用）。 */
    @Test
    void createMaterialTreatsZeroPurchasePriceAsNull() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        InventoryMaterialPo created = service.createMaterial(new InventoryApplicationService.MaterialCommand(
                3L, "M-1", "可乐", "饮品", "瓶", BigDecimal.ZERO, BigDecimal.ZERO, null, null, null));

        assertNull(created.getPurchasePrice());
    }

    @Test
    void createMaterialRejectsNegativePurchasePrice() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createMaterial(new InventoryApplicationService.MaterialCommand(
                        3L, "M-1", "可乐", "饮品", "瓶", BigDecimal.ZERO, new BigDecimal("-1"), null, null, null)));

        assertEquals("PURCHASE_PRICE_INVALID", error.getCode());
        assertEquals("采购价（最小货币单位）不能为负", error.getMessage());
        verify(materialMapper, never()).insert(any(InventoryMaterialPo.class));
    }

    /** 上限 10^13 分：等于上限合法，超过即 400（防止把「元」当「分」提交）。 */
    @Test
    void createMaterialRejectsPurchasePriceAboveUpperBound() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        InventoryMaterialPo atLimit = service.createMaterial(new InventoryApplicationService.MaterialCommand(
                3L, "M-1", "可乐", "饮品", "瓶", BigDecimal.ZERO, new BigDecimal("10000000000000"), null, null, null));
        assertEquals(0, atLimit.getPurchasePrice().compareTo(new BigDecimal("10000000000000")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createMaterial(new InventoryApplicationService.MaterialCommand(
                        3L, "M-2", "雪碧", "饮品", "瓶", BigDecimal.ZERO, new BigDecimal("10000000000001"), null, null, null)));
        assertEquals("PURCHASE_PRICE_INVALID", error.getCode());
        assertEquals("采购价（最小货币单位）超出上限", error.getMessage());
    }

    /** 编辑改采购价：> 0 覆盖为新值。 */
    @Test
    void updateMaterialReplacesPurchasePrice() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        InventoryMaterialPo existing = existingMaterial();
        existing.setPurchasePrice(new BigDecimal("350"));
        when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        InventoryMaterialPo updated = service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                null, null, null, null, null, null, new BigDecimal("420"), null, null, null));

        assertEquals(0, updated.getPurchasePrice().compareTo(new BigDecimal("420")));
        LambdaUpdateWrapper<InventoryMaterialPo> update = capturedUpdate();
        assertTrue(update.getSqlSet().contains("purchase_price"), update.getSqlSet());
        assertTrue(update.getSqlSet().contains("currency_code"), "改写采购价必须同源刷新币种快照");
        assertTrue(update.getParamNameValuePairs().values().contains(new BigDecimal("420")), update.getSqlSet());
    }

    /** 编辑置空：沿用「0 = 清空」约定，归一为 null 并**显式下发 NULL**（updateById 会被 NOT_NULL 策略跳过）。 */
    @Test
    void updateMaterialClearsPurchasePriceWithZero() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        InventoryMaterialPo existing = existingMaterial();
        existing.setPurchasePrice(new BigDecimal("350"));
        when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        InventoryMaterialPo updated = service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                null, null, null, null, null, null, BigDecimal.ZERO, null, null, null));

        assertNull(updated.getPurchasePrice());
        LambdaUpdateWrapper<InventoryMaterialPo> update = capturedUpdate();
        assertTrue(update.getSqlSet().contains("purchase_price"), update.getSqlSet());
        assertTrue(update.getParamNameValuePairs().values().contains(null), "取消定价必须下发 NULL");
    }

    /** null = 不修改：不传采购价时保留库中原值。 */
    @Test
    void updateMaterialKeepsPurchasePriceWhenNotProvided() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        InventoryMaterialPo existing = existingMaterial();
        existing.setPurchasePrice(new BigDecimal("350"));
        when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        InventoryMaterialPo updated = service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                null, null, "新名", null, null, null, null, null, null, null));

        assertEquals("新名", updated.getName());
        assertEquals(0, updated.getPurchasePrice().compareTo(new BigDecimal("350")));
    }

    @Test
    void updateMaterialRejectsInvalidPurchasePrice() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        InventoryMaterialPo existing = existingMaterial();
        when(materialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        BusinessException negative = assertThrows(BusinessException.class,
                () -> service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                        null, null, null, null, null, null, new BigDecimal("-0.01"), null, null, null)));
        assertEquals("PURCHASE_PRICE_INVALID", negative.getCode());

        BusinessException tooLarge = assertThrows(BusinessException.class,
                () -> service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                        null, null, null, null, null, null, new BigDecimal("10000000000001"), null, null, null)));
        assertEquals("PURCHASE_PRICE_INVALID", tooLarge.getCode());
        verify(materialMapper, never()).update(isNull(), any());
    }

    private static InventoryMaterialPo existingMaterial() {
        InventoryMaterialPo existing = new InventoryMaterialPo();
        existing.setId(10L); existing.setTenantId(1L); existing.setStoreId(3L); existing.setName("物料");
        return existing;
    }
}

package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.currency.CurrencyResolver;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.dto.InventoryMaterialView;
import io.openware.platform.order.api.dto.InventoryTransactionView;
import io.openware.platform.order.domain.catalog.model.CatalogItem;
import io.openware.platform.order.domain.catalog.model.Product;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.domain.catalog.repository.ProductRepository.ProductQuery;
import io.openware.platform.order.domain.inventory.model.InventoryMaterial;
import io.openware.platform.order.domain.inventory.model.InventoryScope;
import io.openware.platform.order.domain.inventory.model.InventoryStock;
import io.openware.platform.order.domain.inventory.model.InventoryTransaction;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository.AuditSnapshot;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository.Persisted;
import io.openware.platform.order.domain.inventory.repository.InventoryStockRepository;
import io.openware.platform.order.domain.inventory.repository.InventoryTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 物料档案与库存变更的应用层行为（Mockito 单测，不连库）。
 *
 * <p>迁移后的断言口径：服务只与**领域仓储端口**交互，因此这里断言的是「交给端口的是什么」
 * （领域模型的字段、幂等键、数量）而不是「生成的 SQL 长什么样」——SQL 形状（显式列集合、
 * 非列字段绝不进 SET、json 列的 TypeHandler）改由
 * {@code InventoryMaterialRepositoryAdapterTest} 用真实适配器 + mock mapper 钉住。
 * 这不是放宽要求：原来用 {@code LambdaUpdateWrapper} 断言列名，现在等价断言的是**解析后的完整状态**
 * 被交给端口，而端口契约要求适配器把这些列整组显式写出（见端口 Javadoc）。
 */
class InventoryApplicationServiceTest {
    private final InventoryMaterialRepository materialRepository = mock(InventoryMaterialRepository.class);
    private final InventoryStockRepository stockRepository = mock(InventoryStockRepository.class);
    private final InventoryTransactionRepository transactionRepository = mock(InventoryTransactionRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final CatalogItemRepository catalogItemRepository = mock(CatalogItemRepository.class);
    private final InventoryApplicationService service = new InventoryApplicationService(
            materialRepository, stockRepository, transactionRepository, productRepository, catalogItemRepository);

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    @Test
    void consumeRejectsWhenAvailableStockIsInsufficient() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        when(transactionRepository.findByIdempotency(1L, "k1")).thenReturn(Optional.empty());
        when(materialRepository.findInStore(1L, 3L, 10L)).thenReturn(Optional.of(material(10L, "物料")));
        when(stockRepository.findForUpdate(any(InventoryScope.class), eq(10L))).thenReturn(Optional.of(stock("2", "1")));
        // 条件更新受影响 0 行 = 可用量不足（端口把「受影响行数 > 0」表达成 boolean）。
        when(stockRepository.deductAvailable(any(InventoryScope.class), eq(10L), any(), any())).thenReturn(false);

        assertThrows(RuntimeException.class, () -> service.changeStock(10L, new BigDecimal("2"), "CONSUME", "ORDER_ITEM", "1", "test", "k1"));
        verify(transactionRepository, never()).append(any(InventoryTransaction.class));
    }

    @Test
    void repeatedIdempotencyKeyReturnsOriginalTransaction() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        InventoryTransaction existing = new InventoryTransaction(99L, 1L, 2L, "KTV", 3L, 3L, 10L, "RECEIPT", BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, "USD", "RECEIPT", null, "same",
                "test", LocalDateTime.now());
        when(transactionRepository.findByIdempotency(1L, "same")).thenReturn(Optional.of(existing));

        assertEquals(InventoryTransactionView.from(existing),
                service.changeStock(10L, BigDecimal.ONE, "RECEIPT", "RECEIPT", null, "test", "same"));
        // 幂等重放不读物料、不碰库存：不重复扣减/入库。
        verifyNoInteractions(materialRepository, stockRepository);
    }

    @Test
    void createMaterialPersistsImagesAndExplicitMainImage() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        stubMaterialInsert();

        InventoryMaterialView created = service.createMaterial(new InventoryApplicationService.MaterialCommand(
                3L, "M-1", "纸巾", "耗材", "包", BigDecimal.ONE, null, "客用抽纸，两包装",
                List.of("/api/v1/media-public/b/saas/1/a.png", "/api/v1/media-public/b/saas/1/b.png"), "/api/v1/media-public/b/saas/1/b.png"));

        assertEquals(List.of("/api/v1/media-public/b/saas/1/a.png", "/api/v1/media-public/b/saas/1/b.png"), created.imageUrls());
        assertEquals("/api/v1/media-public/b/saas/1/b.png", created.mainImageUrl());
        assertEquals("客用抽纸，两包装", created.description());
        // 交给端口的聚合与响应一致（端口只做落库，不再有第二个字段来源）。
        InventoryMaterial handed = capturedInsert();
        assertEquals(List.of("/api/v1/media-public/b/saas/1/a.png", "/api/v1/media-public/b/saas/1/b.png"), handed.imageUrls());
        assertNull(handed.id());
    }

    @Test
    void createMaterialWithoutDescriptionKeepsNull() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        stubMaterialInsert();

        InventoryMaterialView created = service.createMaterial(new InventoryApplicationService.MaterialCommand(
                3L, "M-1", "纸巾", "耗材", "包", BigDecimal.ONE, null, null, null, null));

        assertNull(created.description());
        assertNull(capturedInsert().description());
    }

    /** 描述超长必须 400（MATERIAL_INVALID）并给出明确中文消息，不能落库后被数据库截断。 */
    @Test
    void createMaterialRejectsTooLongDescription() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createMaterial(new InventoryApplicationService.MaterialCommand(
                        3L, "M-1", "纸巾", "耗材", "包", BigDecimal.ONE, null, "描".repeat(256), null, null)));

        assertEquals("MATERIAL_INVALID", error.getCode());
        assertEquals("描述长度不能超过 255 个字符", error.getMessage());
        verify(materialRepository, never()).insert(any(InventoryMaterial.class));
    }

    @Test
    void createMaterialRejectsMainImageOutsideImages() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createMaterial(new InventoryApplicationService.MaterialCommand(
                        3L, "M-1", "纸巾", "耗材", "包", BigDecimal.ONE, null, null, List.of("/a.png"), "/b.png")));

        assertEquals("MATERIAL_INVALID", error.getCode());
        assertEquals("主图必须是已上传图片中的一张", error.getMessage());
        verify(materialRepository, never()).insert(any(InventoryMaterial.class));
    }

    @Test
    void createMaterialRejectsMoreThanNineImages() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        List<String> tenImages = java.util.stream.IntStream.rangeClosed(1, 10).mapToObj(index -> "/" + index + ".png").toList();

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createMaterial(new InventoryApplicationService.MaterialCommand(
                        3L, "M-1", "纸巾", "耗材", "包", BigDecimal.ONE, null, null, tenImages, null)));

        assertEquals("MATERIAL_INVALID", error.getCode());
        verify(materialRepository, never()).insert(any(InventoryMaterial.class));
    }

    @Test
    void updateMaterialReplacesImagesAndDefaultsMainImageToFirst() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        when(materialRepository.findInStore(1L, 3L, 10L)).thenReturn(Optional.of(material(10L, "旧名")));
        stubMaterialUpdate();

        InventoryMaterialView updated = service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                null, null, "新名", null, null, null, null, null, List.of("/a.png", "/b.png"), null));

        assertEquals("新名", updated.name());
        assertEquals(List.of("/a.png", "/b.png"), updated.imageUrls());
        assertEquals("/a.png", updated.mainImageUrl());
        // 端口收到的必须是「解析后的完整可编辑状态」：适配器据此把这几列整组显式写出（见端口 update 的说明）。
        InventoryMaterial handed = capturedUpdate();
        assertEquals("新名", handed.name());
        assertEquals(List.of("/a.png", "/b.png"), handed.imageUrls());
        assertEquals("/a.png", handed.mainImageUrl());
    }

    @Test
    void updateMaterialReplacesDescription() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        when(materialRepository.findInStore(1L, 3L, 10L)).thenReturn(Optional.of(material(10L, "物料", "旧描述")));
        stubMaterialUpdate();

        InventoryMaterialView updated = service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                null, null, null, null, null, null, null, "新描述", null, null));

        assertEquals("新描述", updated.description());
        assertEquals("新描述", capturedUpdate().description());
    }

    /**
     * 运营把描述清空时前端传空串，应归一为 null 落库，而不是存一个空白串。
     * 回归：交给端口的描述必须是 NULL —— 适配器把可编辑列整组显式写出（不是 {@code updateById} 的
     * {@code NOT_NULL} 部分更新），因此 NULL 真的会进 SET，不会出现「响应已清空、库里还在」。
     */
    @Test
    void updateMaterialClearsDescriptionWithBlankText() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        when(materialRepository.findInStore(1L, 3L, 10L)).thenReturn(Optional.of(material(10L, "物料", "旧描述")));
        stubMaterialUpdate();

        InventoryMaterialView updated = service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                null, null, null, null, null, null, null, "   ", null, null));

        assertNull(updated.description());
        assertNull(capturedUpdate().description(),
                "清空描述必须把 null 交给端口（updateById 会把 null 整列跳过，写不进 NULL）");
    }

    @Test
    void updateMaterialRejectsTooLongDescription() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        when(materialRepository.findInStore(1L, 3L, 10L)).thenReturn(Optional.of(material(10L, "物料")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                        null, null, null, null, null, null, null, "描".repeat(256), null, null)));

        assertEquals("MATERIAL_INVALID", error.getCode());
        assertEquals("描述长度不能超过 255 个字符", error.getMessage());
        verify(materialRepository, never()).update(any(InventoryMaterial.class), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void updateMaterialRejectsMainImageOutsideImages() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        when(materialRepository.findInStore(1L, 3L, 10L)).thenReturn(Optional.of(material(10L, "物料")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                        null, null, null, null, null, null, null, null, List.of("/a.png"), "/zzz.png")));

        assertEquals("MATERIAL_INVALID", error.getCode());
        verify(materialRepository, never()).update(any(InventoryMaterial.class), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void updateMaterialRejectsMissingMaterial() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        when(materialRepository.findInStore(1L, 3L, 10L)).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                        null, null, "x", null, null, null, null, null, null, null)));

        assertEquals("MATERIAL_NOT_FOUND", error.getCode());
    }

    /** 物料换图后，镜像该物料的点单目录项要跟着有图（否则点单页还是旧图/无图）。 */
    @Test
    void updateMaterialImagesSyncToLinkedCatalogItem() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        when(materialRepository.findInStore(1L, 3L, 10L))
                .thenReturn(Optional.of(material(10L, "物料", null, List.of("/old.png"), "/old.png")));
        stubMaterialUpdate();
        when(productRepository.find(any(ProductQuery.class)))
                .thenReturn(List.of(product(50L, 10L, 70L, null, null)));
        when(catalogItemRepository.findById(70L)).thenReturn(Optional.of(catalogItem(70L)));

        service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                null, null, null, null, null, null, null, null, List.of("/new-a.png", "/new-b.png"), "/new-b.png"));

        ArgumentCaptor<CatalogItem> captor = ArgumentCaptor.forClass(CatalogItem.class);
        verify(catalogItemRepository).update(captor.capture());
        assertEquals(List.of("/new-a.png", "/new-b.png"), captor.getValue().imageUrls());
        assertEquals("/new-b.png", captor.getValue().mainImageUrl());
    }

    /** 商品自己有图时目录项以商品图为准，物料换图不能把商品图冲掉。 */
    @Test
    void updateMaterialImagesDoNotOverwriteCatalogItemBackedByProductImages() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        when(materialRepository.findInStore(1L, 3L, 10L)).thenReturn(Optional.of(material(10L, "物料")));
        stubMaterialUpdate();
        when(productRepository.find(any(ProductQuery.class)))
                .thenReturn(List.of(product(50L, 10L, 70L, List.of("/product.png"), "/product.png")));

        service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                null, null, null, null, null, null, null, null, List.of("/material.png"), null));

        verify(catalogItemRepository, never()).update(any(CatalogItem.class));
    }

    // ---- 采购价（ord_inventory_material.purchase_price，最小货币单位：分）----

    /** 新建带采购价：原样落库（分），不做任何单位换算。 */
    @Test
    void createMaterialPersistsPurchasePrice() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        stubMaterialInsert();

        InventoryMaterialView created = service.createMaterial(new InventoryApplicationService.MaterialCommand(
                3L, "M-1", "可乐", "饮品", "瓶", BigDecimal.ZERO, new BigDecimal("350"), null, null, null));

        assertEquals(0, created.purchasePrice().compareTo(new BigDecimal("350")));
        assertEquals(0, capturedInsert().purchasePrice().compareTo(new BigDecimal("350")));
    }

    /** 采购价可空：不传即「未维护」，落库 null。 */
    @Test
    void createMaterialWithoutPurchasePriceKeepsNull() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        stubMaterialInsert();

        InventoryMaterialView created = service.createMaterial(new InventoryApplicationService.MaterialCommand(
                3L, "M-1", "可乐", "饮品", "瓶", BigDecimal.ZERO, null, null, null, null));

        assertNull(created.purchasePrice());
        assertNull(capturedInsert().purchasePrice());
    }

    /** 0 = 清空/未填：落库 null，不写 0 分（避免「0 分」与「未维护」两种含义混用）。 */
    @Test
    void createMaterialTreatsZeroPurchasePriceAsNull() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        stubMaterialInsert();

        InventoryMaterialView created = service.createMaterial(new InventoryApplicationService.MaterialCommand(
                3L, "M-1", "可乐", "饮品", "瓶", BigDecimal.ZERO, BigDecimal.ZERO, null, null, null));

        assertNull(created.purchasePrice());
        assertNull(capturedInsert().purchasePrice());
    }

    @Test
    void createMaterialRejectsNegativePurchasePrice() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createMaterial(new InventoryApplicationService.MaterialCommand(
                        3L, "M-1", "可乐", "饮品", "瓶", BigDecimal.ZERO, new BigDecimal("-1"), null, null, null)));

        assertEquals("PURCHASE_PRICE_INVALID", error.getCode());
        assertEquals("采购价（最小货币单位）不能为负", error.getMessage());
        verify(materialRepository, never()).insert(any(InventoryMaterial.class));
    }

    /** 上限 10^13 分：等于上限合法，超过即 400（防止把「元」当「分」提交）。 */
    @Test
    void createMaterialRejectsPurchasePriceAboveUpperBound() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        stubMaterialInsert();

        InventoryMaterialView atLimit = service.createMaterial(new InventoryApplicationService.MaterialCommand(
                3L, "M-1", "可乐", "饮品", "瓶", BigDecimal.ZERO, new BigDecimal("10000000000000"), null, null, null));
        assertEquals(0, atLimit.purchasePrice().compareTo(new BigDecimal("10000000000000")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createMaterial(new InventoryApplicationService.MaterialCommand(
                        3L, "M-2", "雪碧", "饮品", "瓶", BigDecimal.ZERO, new BigDecimal("10000000000001"), null, null, null)));
        assertEquals("PURCHASE_PRICE_INVALID", error.getCode());
        assertEquals("采购价（最小货币单位）超出上限", error.getMessage());
    }

    /** 编辑改采购价：> 0 覆盖为新值，并同源刷新币种快照。 */
    @Test
    void updateMaterialReplacesPurchasePrice() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        when(materialRepository.findInStore(1L, 3L, 10L))
                .thenReturn(Optional.of(material(10L, "物料", null, null, null, new BigDecimal("350"), "USD")));
        stubMaterialUpdate();

        InventoryMaterialView updated = service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                null, null, null, null, null, null, new BigDecimal("420"), null, null, null));

        assertEquals(0, updated.purchasePrice().compareTo(new BigDecimal("420")));
        InventoryMaterial handed = capturedUpdate();
        assertEquals(0, handed.purchasePrice().compareTo(new BigDecimal("420")));
        assertEquals(CurrencyResolver.currentCode(), handed.currencyCode(), "改写采购价必须同源刷新币种快照");
    }

    /** 编辑置空：沿用「0 = 清空」约定，归一为 null 交给端口（适配器显式 SET NULL）。 */
    @Test
    void updateMaterialClearsPurchasePriceWithZero() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        when(materialRepository.findInStore(1L, 3L, 10L))
                .thenReturn(Optional.of(material(10L, "物料", null, null, null, new BigDecimal("350"), "USD")));
        stubMaterialUpdate();

        InventoryMaterialView updated = service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                null, null, null, null, null, null, BigDecimal.ZERO, null, null, null));

        assertNull(updated.purchasePrice());
        assertNull(capturedUpdate().purchasePrice(), "取消定价必须把 null 交给端口（适配器显式 SET NULL）");
    }

    /** null = 不修改：不传采购价时保留库中原值。 */
    @Test
    void updateMaterialKeepsPurchasePriceWhenNotProvided() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        when(materialRepository.findInStore(1L, 3L, 10L))
                .thenReturn(Optional.of(material(10L, "物料", null, null, null, new BigDecimal("350"), "USD")));
        stubMaterialUpdate();

        InventoryMaterialView updated = service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                null, null, "新名", null, null, null, null, null, null, null));

        assertEquals("新名", updated.name());
        assertEquals(0, updated.purchasePrice().compareTo(new BigDecimal("350")));
        assertEquals(0, capturedUpdate().purchasePrice().compareTo(new BigDecimal("350")));
        assertEquals("USD", capturedUpdate().currencyCode(), "未改价就不该动币种快照");
    }

    @Test
    void updateMaterialRejectsInvalidPurchasePrice() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1, java.util.List.of(), null, "KTV", null));
        when(materialRepository.findInStore(1L, 3L, 10L)).thenReturn(Optional.of(material(10L, "物料")));

        BusinessException negative = assertThrows(BusinessException.class,
                () -> service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                        null, null, null, null, null, null, new BigDecimal("-0.01"), null, null, null)));
        assertEquals("PURCHASE_PRICE_INVALID", negative.getCode());

        BusinessException tooLarge = assertThrows(BusinessException.class,
                () -> service.updateMaterial(10L, new InventoryApplicationService.MaterialCommand(
                        null, null, null, null, null, null, new BigDecimal("10000000000001"), null, null, null)));
        assertEquals("PURCHASE_PRICE_INVALID", tooLarge.getCode());
        verify(materialRepository, never()).update(any(InventoryMaterial.class), org.mockito.ArgumentMatchers.anyInt());
    }

    // ---- 辅助 ----

    /**
     * 物料插入的桩：端口返回「回填主键 + 审计快照」的落库结果，应用层据此装配响应。
     * 领域模型不可变，因此这里必须回一个新实例（旧的 mock mapper 是就地改 PO 的字段，那种写法已不成立）。
     */
    private void stubMaterialInsert() {
        when(materialRepository.insert(any(InventoryMaterial.class))).thenAnswer(invocation -> {
            InventoryMaterial material = invocation.getArgument(0);
            LocalDateTime now = LocalDateTime.now();
            return new Persisted<>(material.withIdAndCreatedAt(9L, now), new AuditSnapshot(null, now, null, now));
        });
    }

    /** 物料更新的桩（同上：回填落库结果，端口签名是 {@code Persisted}）。 */
    private void stubMaterialUpdate() {
        when(materialRepository.update(any(InventoryMaterial.class), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            InventoryMaterial material = invocation.getArgument(0);
            LocalDateTime now = LocalDateTime.now();
            return new Persisted<>(material, new AuditSnapshot(null, material.createdAt(), null, now));
        });
    }

    private InventoryMaterial capturedInsert() {
        ArgumentCaptor<InventoryMaterial> captor = ArgumentCaptor.forClass(InventoryMaterial.class);
        verify(materialRepository).insert(captor.capture());
        return captor.getValue();
    }

    private InventoryMaterial capturedUpdate() {
        ArgumentCaptor<InventoryMaterial> captor = ArgumentCaptor.forClass(InventoryMaterial.class);
        verify(materialRepository).update(captor.capture(), org.mockito.ArgumentMatchers.anyInt());
        return captor.getValue();
    }

    private static InventoryMaterial material(Long id, String name) {
        return material(id, name, null, null, null, null, "USD");
    }

    private static InventoryMaterial material(Long id, String name, String description) {
        return material(id, name, description, null, null, null, "USD");
    }

    private static InventoryMaterial material(Long id, String name, String description, List<String> images,
                                              String mainImage) {
        return material(id, name, description, images, mainImage, null, "USD");
    }

    private static InventoryMaterial material(Long id, String name, String description, List<String> images,
                                              String mainImage, BigDecimal purchasePrice, String currency) {
        return new InventoryMaterial(id, 1L, 3L, "M-1", name, "耗材", "包", description, BigDecimal.ZERO,
                purchasePrice, currency, images, mainImage, "ACTIVE", 0, LocalDateTime.now());
    }

    private static InventoryStock stock(String onHand, String reserved) {
        return new InventoryStock(20L, 1L, 2L, "KTV", 3L, 3L, 10L, new BigDecimal(onHand), new BigDecimal(reserved),
                BigDecimal.ZERO, "USD", 0);
    }

    private static Product product(Long id, Long materialId, Long catalogItemId, List<String> images, String mainImage) {
        return new Product(id, 1L, 3L, "P-" + id, "商品" + id, "耗材", "PRODUCT", "瓶", new BigDecimal("1000"),
                materialId, null, true, catalogItemId, "ON_SHELF", 0, null, images, mainImage, 0);
    }

    private static CatalogItem catalogItem(Long id) {
        return new CatalogItem(id, 50L, 1L, 3L, "耗材", "PRODUCT", "商品50", "瓶", new BigDecimal("1000"),
                null, List.of(), null, "ACTIVE", true, 0);
    }
}

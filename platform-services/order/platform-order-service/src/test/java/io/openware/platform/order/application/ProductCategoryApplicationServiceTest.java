package io.openware.platform.order.application;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.dto.ProductCategoryView;
import io.openware.platform.order.domain.catalog.model.ProductCategory;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.AuditSnapshot;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.Persisted;
import io.openware.platform.order.domain.catalog.repository.ProductCategoryRepository;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品分类字典：重名 409、改名同步引用、被商品引用不可删。
 *
 * <p><b>迁移后的断言口径</b>：分类/商品/目录项都走领域端口，夹具是不可变 record。
 * 「改名同步引用」不再断言「某两个 Mapper 各收到一次 update」，而是断言
 * {@code ProductRepository#renameCategory} 与 {@code CatalogItemRepository#renameCategory}
 * 各收到一次**单条条件 UPDATE**（业务语义命名，不暴露 Wrapper）。
 */
class ProductCategoryApplicationServiceTest {

    private final ProductCategoryRepository categoryRepository = mock(ProductCategoryRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final CatalogItemRepository catalogItemRepository = mock(CatalogItemRepository.class);
    private final ProductCategoryApplicationService service =
            new ProductCategoryApplicationService(categoryRepository, productRepository, catalogItemRepository);

    @BeforeEach
    void stubPersistence() {
        when(categoryRepository.insert(any(ProductCategory.class))).thenAnswer(invocation ->
                persisted(invocation.getArgument(0, ProductCategory.class).withId(9L)));
        when(categoryRepository.update(any(ProductCategory.class))).thenAnswer(invocation ->
                persisted(invocation.getArgument(0, ProductCategory.class)));
    }

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    @Test
    void createTrimsNameAndDefaultsSortOrderAndStatus() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        ProductCategoryView created = service.create(new ProductCategoryApplicationService.CategoryCommand("  酒水  ", null, null));

        assertEquals("酒水", created.name());
        assertEquals(0, created.sortOrder());
        assertEquals("ACTIVE", created.status());
        assertEquals(3L, created.storeId());
        verify(categoryRepository).insert(any(ProductCategory.class));
    }

    @Test
    void createRejectsBlankName() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(new ProductCategoryApplicationService.CategoryCommand("   ", 0, null)));

        assertEquals("PRODUCT_CATEGORY_INVALID", error.getCode());
        assertEquals("分类名称不能为空", error.getMessage());
        verify(categoryRepository, never()).insert(any(ProductCategory.class));
    }

    @Test
    void createRejectsTooLongName() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(new ProductCategoryApplicationService.CategoryCommand("分".repeat(65), 0, null)));

        assertEquals("PRODUCT_CATEGORY_INVALID", error.getCode());
        assertEquals("分类名称长度不能超过 64 个字符", error.getMessage());
    }

    /** 同门店重名必须 409（可读中文消息），不能落库。 */
    @Test
    void createRejectsDuplicatedNameWithConflictStatus() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(categoryRepository.findByName(1L, 3L, "酒水")).thenReturn(Optional.of(category(9L, "酒水", 1, "ACTIVE")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(new ProductCategoryApplicationService.CategoryCommand("酒水", 1, null)));

        assertEquals("PRODUCT_CATEGORY_DUPLICATED", error.getCode());
        assertEquals("分类名称已存在：酒水", error.getMessage());
        assertEquals(HttpStatus.CONFLICT, new GlobalExceptionHandler().handleBusiness(error).getStatusCode());
        verify(categoryRepository, never()).insert(any(ProductCategory.class));
    }

    /** 改名采用「同步改名」：引用旧名的商品与点单目录项都要一起改成新名。 */
    @Test
    void updateRenamesReferencedProductsAndCatalogItems() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(categoryRepository.findById(9L)).thenReturn(Optional.of(category(9L, "酒水", 1, "ACTIVE")));
        when(categoryRepository.findByName(1L, 3L, "酒水饮料")).thenReturn(Optional.empty());

        ProductCategoryView updated = service.update(9L,
                new ProductCategoryApplicationService.CategoryCommand("酒水饮料", null, null));

        assertEquals("酒水饮料", updated.name());
        verify(productRepository).renameCategory(1L, 3L, "酒水", "酒水饮料");
        verify(catalogItemRepository).renameCategory(1L, 3L, "酒水", "酒水饮料");
        verify(categoryRepository).update(any(ProductCategory.class));
    }

    @Test
    void updateRejectsRenamingToAnotherExistingName() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(categoryRepository.findById(9L)).thenReturn(Optional.of(category(9L, "酒水", 1, "ACTIVE")));
        when(categoryRepository.findByName(1L, 3L, "热饮")).thenReturn(Optional.of(category(10L, "热饮", 2, "ACTIVE")));

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(9L,
                new ProductCategoryApplicationService.CategoryCommand("热饮", null, null)));

        assertEquals("PRODUCT_CATEGORY_DUPLICATED", error.getCode());
        verify(categoryRepository, never()).update(any(ProductCategory.class));
        verify(productRepository, never()).renameCategory(any(), any(), any(), any());
    }

    @Test
    void updateChangesSortOrderAndDisablesCategory() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(categoryRepository.findById(9L)).thenReturn(Optional.of(category(9L, "酒水", 1, "ACTIVE")));

        ProductCategoryView updated = service.update(9L,
                new ProductCategoryApplicationService.CategoryCommand(null, 5, "disabled"));

        assertEquals(5, updated.sortOrder());
        assertEquals("DISABLED", updated.status());
    }

    @Test
    void updateRejectsUnknownStatus() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(categoryRepository.findById(9L)).thenReturn(Optional.of(category(9L, "酒水", 1, "ACTIVE")));

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(9L,
                new ProductCategoryApplicationService.CategoryCommand(null, null, "OFF")));

        assertEquals("PRODUCT_CATEGORY_INVALID", error.getCode());
        assertEquals("分类状态只能是 ACTIVE 或 DISABLED", error.getMessage());
    }

    /** 被商品引用时禁止删除：409 + 中文消息，且不能真的删掉。 */
    @Test
    void deleteRejectsCategoryStillReferencedByProducts() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(categoryRepository.findById(9L)).thenReturn(Optional.of(category(9L, "酒水", 1, "ACTIVE")));
        when(productRepository.countByCategory(1L, 3L, "酒水")).thenReturn(2L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.delete(9L));

        assertEquals("PRODUCT_CATEGORY_IN_USE", error.getCode());
        assertEquals("分类「酒水」下仍有 2 个商品，请先改到其他分类再删除", error.getMessage());
        assertEquals(HttpStatus.CONFLICT, new GlobalExceptionHandler().handleBusiness(error).getStatusCode());
        verify(categoryRepository, never()).deleteById(anyLong());
    }

    @Test
    void deleteRemovesUnreferencedCategory() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(categoryRepository.findById(9L)).thenReturn(Optional.of(category(9L, "酒水", 1, "ACTIVE")));
        when(productRepository.countByCategory(1L, 3L, "酒水")).thenReturn(0L);

        service.delete(9L);

        verify(categoryRepository).deleteById(9L);
    }

    @Test
    void deleteRejectsMissingCategory() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(categoryRepository.findById(9L)).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class, () -> service.delete(9L));

        assertEquals("PRODUCT_CATEGORY_NOT_FOUND", error.getCode());
    }

    @Test
    void listOnlyReturnsRequestedStatusInSortOrder() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(categoryRepository.find(eq(1L), eq(3L), eq("ACTIVE")))
                .thenReturn(List.of(category(9L, "酒水", 1, "ACTIVE")));

        List<ProductCategoryView> result = service.list(3L, "ACTIVE");

        assertEquals(1, result.size());
        assertEquals("酒水", result.getFirst().name());
    }

    @Test
    void listRejectsAnotherStore() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        BusinessException error = assertThrows(BusinessException.class, () -> service.list(99L, null));

        assertEquals("STORE_SCOPE_DENIED", error.getCode());
    }

    private static ProductCategory category(Long id, String name, Integer sortOrder, String status) {
        return new ProductCategory(id, 1L, 3L, name, sortOrder, status);
    }

    /** 端口返回值：聚合 + 审计快照（领域模型不承载审计列）。 */
    private static Persisted<ProductCategory> persisted(ProductCategory category) {
        LocalDateTime now = LocalDateTime.of(2026, 9, 19, 20, 0, 0);
        return new Persisted<>(category, new AuditSnapshot(null, now, null, now));
    }
}

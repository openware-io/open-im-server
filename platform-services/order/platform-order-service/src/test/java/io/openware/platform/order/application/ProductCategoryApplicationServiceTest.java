package io.openware.platform.order.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.handler.GlobalExceptionHandler;
import io.openware.platform.order.infra.persistence.mapper.CatalogItemMapper;
import io.openware.platform.order.infra.persistence.mapper.ProductCategoryMapper;
import io.openware.platform.order.infra.persistence.mapper.ProductMapper;
import io.openware.platform.order.infra.persistence.po.ProductCategoryPo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 商品分类字典：重名 409、改名同步引用、被商品引用不可删。 */
class ProductCategoryApplicationServiceTest {

    private final ProductCategoryMapper categoryMapper = mock(ProductCategoryMapper.class);
    private final ProductMapper productMapper = mock(ProductMapper.class);
    private final CatalogItemMapper catalogItemMapper = mock(CatalogItemMapper.class);
    private final ProductCategoryApplicationService service =
            new ProductCategoryApplicationService(categoryMapper, productMapper, catalogItemMapper);

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    @Test
    void createTrimsNameAndDefaultsSortOrderAndStatus() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        ProductCategoryPo created = service.create(new ProductCategoryApplicationService.CategoryCommand("  酒水  ", null, null));

        assertEquals("酒水", created.getName());
        assertEquals(0, created.getSortOrder());
        assertEquals("ACTIVE", created.getStatus());
        assertEquals(3L, created.getStoreId());
        verify(categoryMapper).insert(created);
    }

    @Test
    void createRejectsBlankName() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(new ProductCategoryApplicationService.CategoryCommand("   ", 0, null)));

        assertEquals("PRODUCT_CATEGORY_INVALID", error.getCode());
        assertEquals("分类名称不能为空", error.getMessage());
        verify(categoryMapper, never()).insert(any(ProductCategoryPo.class));
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
        ProductCategoryPo existing = new ProductCategoryPo();
        existing.setId(9L); existing.setTenantId(1L); existing.setStoreId(3L); existing.setName("酒水");
        when(categoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(new ProductCategoryApplicationService.CategoryCommand("酒水", 1, null)));

        assertEquals("PRODUCT_CATEGORY_DUPLICATED", error.getCode());
        assertEquals("分类名称已存在：酒水", error.getMessage());
        assertEquals(HttpStatus.CONFLICT, new GlobalExceptionHandler().handleBusiness(error).getStatusCode());
        verify(categoryMapper, never()).insert(any(ProductCategoryPo.class));
    }

    /** 改名采用「同步改名」：引用旧名的商品与点单目录项都要一起改成新名。 */
    @Test
    void updateRenamesReferencedProductsAndCatalogItems() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        ProductCategoryPo existing = category(9L, "酒水", 1, "ACTIVE");
        when(categoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing, null);

        ProductCategoryPo updated = service.update(9L,
                new ProductCategoryApplicationService.CategoryCommand("酒水饮料", null, null));

        assertEquals("酒水饮料", updated.getName());
        verify(productMapper).update(any(), any());
        verify(catalogItemMapper).update(any(), any());
        verify(categoryMapper).updateById(existing);
    }

    @Test
    void updateRejectsRenamingToAnotherExistingName() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        ProductCategoryPo existing = category(9L, "酒水", 1, "ACTIVE");
        ProductCategoryPo other = category(10L, "热饮", 2, "ACTIVE");
        when(categoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing, other);

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(9L,
                new ProductCategoryApplicationService.CategoryCommand("热饮", null, null)));

        assertEquals("PRODUCT_CATEGORY_DUPLICATED", error.getCode());
        verify(categoryMapper, never()).updateById(any(ProductCategoryPo.class));
        verify(productMapper, never()).update(any(), any());
    }

    @Test
    void updateChangesSortOrderAndDisablesCategory() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        ProductCategoryPo existing = category(9L, "酒水", 1, "ACTIVE");
        when(categoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        ProductCategoryPo updated = service.update(9L,
                new ProductCategoryApplicationService.CategoryCommand(null, 5, "disabled"));

        assertEquals(5, updated.getSortOrder());
        assertEquals("DISABLED", updated.getStatus());
    }

    @Test
    void updateRejectsUnknownStatus() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        ProductCategoryPo existing = category(9L, "酒水", 1, "ACTIVE");
        when(categoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(9L,
                new ProductCategoryApplicationService.CategoryCommand(null, null, "OFF")));

        assertEquals("PRODUCT_CATEGORY_INVALID", error.getCode());
        assertEquals("分类状态只能是 ACTIVE 或 DISABLED", error.getMessage());
    }

    /** 被商品引用时禁止删除：409 + 中文消息，且不能真的删掉。 */
    @Test
    void deleteRejectsCategoryStillReferencedByProducts() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(categoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(category(9L, "酒水", 1, "ACTIVE"));
        when(productMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.delete(9L));

        assertEquals("PRODUCT_CATEGORY_IN_USE", error.getCode());
        assertEquals("分类「酒水」下仍有 2 个商品，请先改到其他分类再删除", error.getMessage());
        assertEquals(HttpStatus.CONFLICT, new GlobalExceptionHandler().handleBusiness(error).getStatusCode());
        verify(categoryMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteRemovesUnreferencedCategory() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(categoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(category(9L, "酒水", 1, "ACTIVE"));
        when(productMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        service.delete(9L);

        verify(categoryMapper).deleteById(9L);
    }

    @Test
    void deleteRejectsMissingCategory() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(categoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class, () -> service.delete(9L));

        assertEquals("PRODUCT_CATEGORY_NOT_FOUND", error.getCode());
    }

    @Test
    void listOnlyReturnsRequestedStatusInSortOrder() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));
        when(categoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(category(9L, "酒水", 1, "ACTIVE")));

        List<ProductCategoryPo> result = service.list(3L, "ACTIVE");

        assertEquals(1, result.size());
        assertEquals("酒水", result.getFirst().getName());
    }

    @Test
    void listRejectsAnotherStore() {
        TenantContextHolder.set(new TenantContext(1L, 2L, 3L, 4L, 1));

        BusinessException error = assertThrows(BusinessException.class, () -> service.list(99L, null));

        assertEquals("STORE_SCOPE_DENIED", error.getCode());
    }

    private static ProductCategoryPo category(Long id, String name, Integer sortOrder, String status) {
        ProductCategoryPo po = new ProductCategoryPo();
        po.setId(id); po.setTenantId(1L); po.setStoreId(3L); po.setName(name);
        po.setSortOrder(sortOrder); po.setStatus(status);
        return po;
    }
}

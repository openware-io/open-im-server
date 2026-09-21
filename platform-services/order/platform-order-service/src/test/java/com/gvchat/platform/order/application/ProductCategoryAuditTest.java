package com.gvchat.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.audit.AuditActions;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.infra.persistence.mapper.CatalogItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.ProductCategoryMapper;
import com.gvchat.platform.order.infra.persistence.mapper.ProductMapper;
import com.gvchat.platform.order.infra.persistence.po.ProductCategoryPo;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 商品分类写操作的审计覆盖（2026-09 补齐）。
 *
 * <p>背景：{@code AuditActions} 里 {@code product.category.create/update/delete} 三个标签早已登记，
 * 但代码里没有任何调用点上报过——后台改名会连带改写商品与点单目录的分类引用，
 * 删除分类也只在业务规则上被拦，操作日志里却查不到任何痕迹。
 * 这里把「成功 + 失败都必须留痕」固化成回归：漏掉任一条该用例即失败。
 */
class ProductCategoryAuditTest {

    private static final long TENANT_ID = 1L;
    private static final Long STORE_ID = 3L;

    private ProductCategoryMapper categoryMapper;
    private ProductMapper productMapper;
    private CatalogItemMapper catalogItemMapper;
    private AuditClient auditClient;
    private ProductCategoryApplicationService service;

    @BeforeEach
    void setUp() {
        categoryMapper = mock(ProductCategoryMapper.class);
        productMapper = mock(ProductMapper.class);
        catalogItemMapper = mock(CatalogItemMapper.class);
        auditClient = mock(AuditClient.class);
        service = new ProductCategoryApplicationService(categoryMapper, productMapper, catalogItemMapper, auditClient);
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void createWritesSucceededAudit() {
        when(categoryMapper.selectOne(any())).thenReturn(null);

        ProductCategoryPo created = service.create(command("酒水", 5, null));

        AuditClient.AuditRecord record = captured();
        assertEquals("product.category.create", record.action());
        // actionLabel 由 AuditClient.buildBody 用 AuditActions 补全（单测里 AuditClient 是 mock，不跑那段）；
        // 这里断言动作码已登记，避免前端筛选/列表里出现裸码（标签本身由 AuditActionsTest 钉住）。
        assertNotEquals(record.action(), AuditActions.labelOf(record.action()));
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertEquals("ord_product_category", record.resourceType());
        assertEquals(TENANT_ID, record.tenantId());
        assertEquals(STORE_ID, record.storeId());
        assertEquals("酒水", record.resourceName());
        assertNotNull(created);
    }

    @Test
    void createWritesFailureAuditWithStableErrorCode() {
        ProductCategoryPo existing = category(7L, "酒水");
        when(categoryMapper.selectOne(any())).thenReturn(existing);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(command("酒水", 0, null)));

        assertEquals("PRODUCT_CATEGORY_DUPLICATED", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("product.category.create", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("PRODUCT_CATEGORY_DUPLICATED", record.errorCode());
        assertNull(record.idempotencyKey());
    }

    @Test
    void updateWritesSucceededAuditWithStableIdempotencyKey() {
        ProductCategoryPo existing = category(10L, "酒水");
        when(categoryMapper.selectOne(any())).thenReturn(existing);

        service.update(10L, command("饮品", 2, "ACTIVE"));

        AuditClient.AuditRecord record = captured();
        assertEquals("product.category.update", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertEquals("10", record.resourceId());
        assertEquals("product.category.update:10", record.idempotencyKey());
    }

    @Test
    void updateWritesFailureAuditWhenCategoryMissing() {
        when(categoryMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.update(99L, command("饮品", null, null)));

        assertEquals("PRODUCT_CATEGORY_NOT_FOUND", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("product.category.update", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("PRODUCT_CATEGORY_NOT_FOUND", record.errorCode());
        assertEquals("99", record.resourceId());
    }

    @Test
    void deleteWritesSucceededAuditAndRemovesRow() {
        ProductCategoryPo existing = category(11L, "酒水");
        when(categoryMapper.selectOne(any())).thenReturn(existing);
        when(productMapper.selectCount(any())).thenReturn(0L);

        service.delete(11L);

        verify(categoryMapper).deleteById(11L);
        AuditClient.AuditRecord record = captured();
        assertEquals("product.category.delete", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertEquals("酒水", record.resourceName());
    }

    @Test
    void deleteWritesFailureAuditWhenStillReferencedByProducts() {
        ProductCategoryPo existing = category(11L, "酒水");
        when(categoryMapper.selectOne(any())).thenReturn(existing);
        when(productMapper.selectCount(any())).thenReturn(3L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.delete(11L));

        assertEquals("PRODUCT_CATEGORY_IN_USE", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("product.category.delete", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("PRODUCT_CATEGORY_IN_USE", record.errorCode());
    }

    /** 分类列表读路径不应产生任何审计（读取类不入库，避免噪声淹没）。 */
    @Test
    void listDoesNotWriteAudit() {
        when(categoryMapper.selectList(any())).thenReturn(List.of());

        service.list(null, null);

        verify(auditClient, never()).recordAsync(any());
    }

    private static ProductCategoryApplicationService.CategoryCommand command(String name, Integer sortOrder, String status) {
        return new ProductCategoryApplicationService.CategoryCommand(name, sortOrder, status);
    }

    private static ProductCategoryPo category(Long id, String name) {
        ProductCategoryPo po = new ProductCategoryPo();
        po.setId(id);
        po.setTenantId(TENANT_ID);
        po.setStoreId(STORE_ID);
        po.setName(name);
        po.setSortOrder(0);
        po.setStatus("ACTIVE");
        return po;
    }

    private AuditClient.AuditRecord captured() {
        ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(captor.capture());
        return captor.getValue();
    }
}

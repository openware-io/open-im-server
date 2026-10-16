package io.openware.platform.order.application;

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

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditActions;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.domain.catalog.model.ProductCategory;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.AuditSnapshot;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.Persisted;
import io.openware.platform.order.domain.catalog.repository.ProductCategoryRepository;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

    private ProductCategoryRepository categoryRepository;
    private ProductRepository productRepository;
    private CatalogItemRepository catalogItemRepository;
    private AuditClient auditClient;
    private ProductCategoryApplicationService service;

    @BeforeEach
    void setUp() {
        categoryRepository = mock(ProductCategoryRepository.class);
        productRepository = mock(ProductRepository.class);
        catalogItemRepository = mock(CatalogItemRepository.class);
        auditClient = mock(AuditClient.class);
        service = new ProductCategoryApplicationService(categoryRepository, productRepository, catalogItemRepository,
                auditClient);
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        when(categoryRepository.insert(any(ProductCategory.class))).thenAnswer(invocation ->
                persisted(invocation.getArgument(0, ProductCategory.class).withId(12L)));
        when(categoryRepository.update(any(ProductCategory.class))).thenAnswer(invocation ->
                persisted(invocation.getArgument(0, ProductCategory.class)));
    }

    /**
     * 注意：{@link ProductCategoryApplicationService} 的 3 参构造器会用
     * {@code AuditClient.disabled()}（关闭态），因此审计断言必须走 4 参构造器注入 mock，
     * 否则会出现「零交互」而不是「漏了一条断言」。
     */
    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void createWritesSucceededAudit() {
        when(categoryRepository.findByName(TENANT_ID, STORE_ID, "酒水")).thenReturn(Optional.empty());

        assertNotNull(service.create(command("酒水", 5, null)));

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
    }

    @Test
    void createWritesFailureAuditWithStableErrorCode() {
        when(categoryRepository.findByName(TENANT_ID, STORE_ID, "酒水"))
                .thenReturn(Optional.of(category(7L, "酒水")));

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
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(category(10L, "酒水")));
        when(categoryRepository.findByName(TENANT_ID, STORE_ID, "饮品")).thenReturn(Optional.empty());

        service.update(10L, command("饮品", 2, "ACTIVE"));

        AuditClient.AuditRecord record = captured();
        assertEquals("product.category.update", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertEquals("10", record.resourceId());
        assertEquals("product.category.update:10", record.idempotencyKey());
    }

    @Test
    void updateWritesFailureAuditWhenCategoryMissing() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

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
        when(categoryRepository.findById(11L)).thenReturn(Optional.of(category(11L, "酒水")));
        when(productRepository.countByCategory(TENANT_ID, STORE_ID, "酒水")).thenReturn(0L);

        service.delete(11L);

        verify(categoryRepository).deleteById(11L);
        AuditClient.AuditRecord record = captured();
        assertEquals("product.category.delete", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertEquals("酒水", record.resourceName());
    }

    @Test
    void deleteWritesFailureAuditWhenStillReferencedByProducts() {
        when(categoryRepository.findById(11L)).thenReturn(Optional.of(category(11L, "酒水")));
        when(productRepository.countByCategory(TENANT_ID, STORE_ID, "酒水")).thenReturn(3L);

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
        when(categoryRepository.find(TENANT_ID, STORE_ID, null)).thenReturn(List.of());

        service.list(null, null);

        verify(auditClient, never()).recordAsync(any());
    }

    private static ProductCategoryApplicationService.CategoryCommand command(String name, Integer sortOrder, String status) {
        return new ProductCategoryApplicationService.CategoryCommand(name, sortOrder, status);
    }

    private static ProductCategory category(Long id, String name) {
        return new ProductCategory(id, TENANT_ID, STORE_ID, name, 0, "ACTIVE");
    }

    /** 端口返回值：聚合 + 审计快照（领域模型不承载审计列）。 */
    private static Persisted<ProductCategory> persisted(ProductCategory category) {
        LocalDateTime now = LocalDateTime.of(2026, 9, 19, 20, 0, 0);
        return new Persisted<>(category, new AuditSnapshot(null, now, null, now));
    }

    private AuditClient.AuditRecord captured() {
        ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(captor.capture());
        return captor.getValue();
    }
}

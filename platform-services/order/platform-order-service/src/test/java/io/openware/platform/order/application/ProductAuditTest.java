package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.dto.ProductView;
import io.openware.platform.order.domain.catalog.model.CatalogItem;
import io.openware.platform.order.domain.catalog.model.Product;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.AuditSnapshot;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.Persisted;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.infra.persistence.mapper.InventoryMaterialMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 商品写操作的失败留痕与上下架成功留痕：
 * 商品是点单目录与售卖价格的来源，改价/上下架失败同样必须可回溯。
 */
class ProductAuditTest {

    private static final long TENANT_ID = 1L;
    private static final Long STORE_ID = 3L;

    private ProductRepository productRepository;
    private CatalogItemRepository catalogItemRepository;
    private InventoryMaterialMapper materialMapper;
    private AuditClient auditClient;
    private ProductApplicationService service;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        catalogItemRepository = mock(CatalogItemRepository.class);
        materialMapper = mock(InventoryMaterialMapper.class);
        auditClient = mock(AuditClient.class);
        service = new ProductApplicationService(productRepository, catalogItemRepository, materialMapper, auditClient);
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
        // 上下架会同步目录项：商品无回指时走「新建目录项」分支，端口返回值必须是聚合+审计快照。
        when(catalogItemRepository.insert(any(CatalogItem.class))).thenAnswer(invocation ->
                persistedCatalog(invocation.getArgument(0, CatalogItem.class)));
        when(productRepository.update(any(Product.class))).thenAnswer(invocation ->
                persisted(invocation.getArgument(0, Product.class)));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void create_writesFailureAuditWithStableErrorCode() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(command("", "纸巾")));

        assertEquals("PRODUCT_INVALID", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("product.create", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("PRODUCT_INVALID", record.errorCode());
        assertNull(record.idempotencyKey());
        // 失败详情不含售价等金额。
        assertEquals(false, record.detailJson().contains("salePrice"));
    }

    @Test
    void update_writesFailureAuditWhenProductMissing() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(99L, command("P-1", "纸巾")));

        assertEquals("PRODUCT_NOT_FOUND", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("product.update", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("PRODUCT_NOT_FOUND", record.errorCode());
    }

    @Test
    void onShelf_writesSucceededAudit() {
        when(productRepository.findById(10L)).thenReturn(Optional.of(product(10L, "DRAFT")));

        ProductView shelved = service.onShelf(10L);

        assertEquals("ON_SHELF", shelved.status());
        AuditClient.AuditRecord record = captured();
        assertEquals("product.publish", record.action());
        assertEquals("商品上架", record.actionLabel());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    }

    @Test
    void onShelf_writesFailureAuditWithStableErrorCode() {
        when(productRepository.findById(10L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.onShelf(10L));

        assertEquals("PRODUCT_NOT_FOUND", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("product.publish", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("PRODUCT_NOT_FOUND", record.errorCode());
        assertNull(record.idempotencyKey());
    }

    @Test
    void offShelf_writesSucceededAudit() {
        when(productRepository.findById(10L)).thenReturn(Optional.of(product(10L, "ON_SHELF")));

        ProductView shelved = service.offShelf(10L);

        assertEquals("OFF_SHELF", shelved.status());
        AuditClient.AuditRecord record = captured();
        assertEquals("product.unpublish", record.action());
        assertEquals("商品下架", record.actionLabel());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    }

    @Test
    void offShelf_writesFailureAuditWithStableErrorCode() {
        when(productRepository.findById(10L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.offShelf(10L));

        assertEquals("PRODUCT_NOT_FOUND", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("product.unpublish", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("PRODUCT_NOT_FOUND", record.errorCode());
    }

    private static Product product(Long id, String status) {
        return new Product(id, TENANT_ID, STORE_ID, "P-1", "纸巾", "其他", "PRODUCT", "份",
                new BigDecimal("500"), null, null, false, null, status, 0, null, List.of(), null, 0);
    }

    /** 端口返回值：聚合 + 审计快照（领域模型不承载审计列）。 */
    private static Persisted<Product> persisted(Product product) {
        return new Persisted<>(product, auditSnapshot());
    }

    /** 目录项端口返回值（新增目录项要回填主键，商品侧据此写回 catalog_item_id）。 */
    private static Persisted<CatalogItem> persistedCatalog(CatalogItem item) {
        return new Persisted<>(item.withId(77L), auditSnapshot());
    }

    private static AuditSnapshot auditSnapshot() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 19, 20, 0, 0);
        return new AuditSnapshot(null, now, null, now);
    }

    private static ProductApplicationService.ProductCommand command(String productCode, String name) {
        return new ProductApplicationService.ProductCommand(STORE_ID, productCode, name, null, null,
                new BigDecimal("500"), null, Boolean.FALSE, 0, null, List.of(), null);
    }

    private AuditClient.AuditRecord captured() {
        ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(captor.capture());
        return captor.getValue();
    }
}

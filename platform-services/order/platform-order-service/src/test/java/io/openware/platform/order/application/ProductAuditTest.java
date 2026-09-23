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
import io.openware.platform.order.infra.persistence.mapper.CatalogItemMapper;
import io.openware.platform.order.infra.persistence.mapper.InventoryMaterialMapper;
import io.openware.platform.order.infra.persistence.mapper.ProductMapper;
import io.openware.platform.order.infra.persistence.po.ProductPo;
import java.math.BigDecimal;
import java.util.List;
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

    private ProductMapper productMapper;
    private CatalogItemMapper catalogItemMapper;
    private InventoryMaterialMapper materialMapper;
    private AuditClient auditClient;
    private ProductApplicationService service;

    @BeforeEach
    void setUp() {
        productMapper = mock(ProductMapper.class);
        catalogItemMapper = mock(CatalogItemMapper.class);
        materialMapper = mock(InventoryMaterialMapper.class);
        auditClient = mock(AuditClient.class);
        service = new ProductApplicationService(productMapper, catalogItemMapper, materialMapper, auditClient);
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
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
        when(productMapper.selectById(99L)).thenReturn(null);

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
        when(productMapper.selectById(10L)).thenReturn(product(10L, "DRAFT"));

        ProductPo shelved = service.onShelf(10L);

        assertEquals("ON_SHELF", shelved.getStatus());
        AuditClient.AuditRecord record = captured();
        assertEquals("product.publish", record.action());
        assertEquals("商品上架", record.actionLabel());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    }

    @Test
    void onShelf_writesFailureAuditWithStableErrorCode() {
        when(productMapper.selectById(10L)).thenReturn(null);

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
        when(productMapper.selectById(10L)).thenReturn(product(10L, "ON_SHELF"));

        ProductPo shelved = service.offShelf(10L);

        assertEquals("OFF_SHELF", shelved.getStatus());
        AuditClient.AuditRecord record = captured();
        assertEquals("product.unpublish", record.action());
        assertEquals("商品下架", record.actionLabel());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    }

    @Test
    void offShelf_writesFailureAuditWithStableErrorCode() {
        when(productMapper.selectById(10L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.offShelf(10L));

        assertEquals("PRODUCT_NOT_FOUND", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("product.unpublish", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("PRODUCT_NOT_FOUND", record.errorCode());
    }

    private static ProductPo product(Long id, String status) {
        ProductPo po = new ProductPo();
        po.setId(id);
        po.setTenantId(TENANT_ID);
        po.setStoreId(STORE_ID);
        po.setName("纸巾");
        po.setProductCode("P-1");
        po.setStatus(status);
        po.setSalePrice(new BigDecimal("500"));
        po.setStockControlled(false);
        po.setSortOrder(0);
        po.setVersion(0);
        return po;
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

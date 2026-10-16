package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.domain.inventory.model.InventoryMaterial;
import io.openware.platform.order.domain.inventory.model.InventoryStock;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository;
import io.openware.platform.order.domain.inventory.repository.InventoryStockRepository;
import io.openware.platform.order.domain.inventory.repository.InventoryTransactionRepository;
import io.openware.platform.order.infra.cache.InventoryAvailabilityCache;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 物料/库存写操作的失败留痕：只覆盖「人主动发起」的入库/调整（与成功路径同一动作映射），
 * 加项出库(CONSUME)/作废回补(REVERSE) 由各自业务入口留痕，不重复上报。
 */
class InventoryAuditTest {

    private static final long TENANT_ID = 1L;
    private static final Long STORE_ID = 3L;

    private InventoryMaterialRepository materialRepository;
    private InventoryStockRepository stockRepository;
    private InventoryTransactionRepository transactionRepository;
    private AuditClient auditClient;
    private InventoryApplicationService service;

    @BeforeEach
    void setUp() {
        materialRepository = mock(InventoryMaterialRepository.class);
        stockRepository = mock(InventoryStockRepository.class);
        transactionRepository = mock(InventoryTransactionRepository.class);
        auditClient = mock(AuditClient.class);
        service = new InventoryApplicationService(materialRepository, stockRepository, transactionRepository,
                mock(ProductRepository.class), mock(CatalogItemRepository.class), new InventoryAvailabilityCache(0L),
                auditClient);
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void createMaterial_writesFailureAuditWithStableErrorCode() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createMaterial(materialCommand(4L, "M-1")));

        assertEquals("STORE_SCOPE_DENIED", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("inventory.material.create", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("STORE_SCOPE_DENIED", record.errorCode());
        assertNull(record.idempotencyKey());
        // 失败详情不含采购价等金额。
        assertEquals(false, record.detailJson().contains("purchasePrice"));
    }

    @Test
    void updateMaterial_writesFailureAuditWhenMaterialMissing() {
        when(materialRepository.findInStore(TENANT_ID, STORE_ID, 10L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateMaterial(10L, materialCommand(STORE_ID, "M-1")));

        assertEquals("MATERIAL_NOT_FOUND", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("inventory.material.update", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("MATERIAL_NOT_FOUND", record.errorCode());
    }

    @Test
    void changeStock_writesFailureAuditForReceipt() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.changeStock(10L, BigDecimal.ZERO, "RECEIPT", "RECEIPT", null, "入库", "k1"));

        assertEquals("INVENTORY_INVALID", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("inventory.receipt.create", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("INVENTORY_INVALID", record.errorCode());
    }

    @Test
    void changeStock_writesFailureAuditForAdjustment() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.changeStock(10L, null, "ADJUST_OUT", "ADJUSTMENT", null, "盘点", "k2"));

        assertEquals("INVENTORY_INVALID", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("inventory.adjust", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("INVENTORY_INVALID", record.errorCode());
    }

    /** 加项出库失败由加项入口留痕：这里不得再写一条同义审计（库存不足也不再重复上报）。 */
    @Test
    void changeStock_consumeFailureIsNotAuditedHere() {
        when(transactionRepository.findByIdempotency(TENANT_ID, "k3")).thenReturn(Optional.empty());
        when(materialRepository.findInStore(TENANT_ID, STORE_ID, 10L))
                .thenReturn(Optional.of(material(10L)));
        when(stockRepository.findForUpdate(TENANT_ID, STORE_ID, 10L)).thenReturn(Optional.of(stock()));
        // 条件更新 0 行 = 库存不足（端口把「受影响行数 > 0」表达成 boolean）。
        when(stockRepository.deductAvailable(eq(TENANT_ID), eq(STORE_ID), eq(10L), any(), any())).thenReturn(false);

        assertThrows(BusinessException.class,
                () -> service.changeStock(10L, new BigDecimal("2"), "CONSUME", "ORDER_ITEM", "1", "加项", "k3"));

        verifyNoInteractions(auditClient);
    }

    private static InventoryMaterial material(Long id) {
        return new InventoryMaterial(id, TENANT_ID, STORE_ID, "M-1", "纸巾", "耗材", "包", null,
                new BigDecimal("100"), null, "USD", null, null, "ACTIVE", 0, LocalDateTime.now());
    }

    private static InventoryStock stock() {
        return new InventoryStock(20L, TENANT_ID, STORE_ID, 10L, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO, "USD", 0);
    }

    private static InventoryApplicationService.MaterialCommand materialCommand(Long storeId, String code) {
        return new InventoryApplicationService.MaterialCommand(storeId, code, "纸巾", "耗材", "包",
                new BigDecimal("100"), null, null, List.of(), null);
    }

    private AuditClient.AuditRecord captured() {
        ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(captor.capture());
        return captor.getValue();
    }
}

package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditActions;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.InventoryApplicationService;
import io.openware.platform.order.infra.persistence.mapper.CatalogItemMapper;
import io.openware.platform.order.infra.persistence.mapper.InventoryMaterialMapper;
import io.openware.platform.order.infra.persistence.mapper.ProductMapper;
import io.openware.platform.order.infra.persistence.po.CatalogItemPo;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 点单目录项写操作的审计覆盖（2026-09 补齐）。
 *
 * <p>背景：目录项是点单页价格与分组的来源，商品写操作一直有留痕，目录项的新建/改价/删除
 * 却一条都没有（platform-order-service 没有 BFF 那样的 /admin/** 全局审计拦截器，
 * 只能由调用点显式上报）。后台「删除商品」（软删 status → INACTIVE）在操作日志里查不到。
 * 这里把「成功 + 失败都必须留痕」「读列表不留痕」固化成回归。
 */
class CatalogItemAuditTest {

    private static final long TENANT_ID = 1L;
    private static final Long STORE_ID = 3L;

    private CatalogItemMapper catalogItemMapper;
    private ProductMapper productMapper;
    private InventoryMaterialMapper materialMapper;
    private InventoryApplicationService inventoryService;
    private AuditClient auditClient;
    private CatalogController controller;

    @BeforeEach
    void setUp() {
        catalogItemMapper = mock(CatalogItemMapper.class);
        productMapper = mock(ProductMapper.class);
        materialMapper = mock(InventoryMaterialMapper.class);
        inventoryService = mock(InventoryApplicationService.class);
        auditClient = mock(AuditClient.class);
        controller = new CatalogController(catalogItemMapper, productMapper, materialMapper, inventoryService,
                auditClient);
        // 写端点先过权限门禁：上下文必须带 tenant.store.manage，否则只会留一条 PERMISSION_DENIED。
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1,
                List.of("tenant.store.manage"), null));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void createWritesSucceededAudit() {
        CatalogItemPo created = controller.create(
                new CatalogController.CatalogItemRequest(STORE_ID, "酒水", "PRODUCT", "青岛啤酒", "瓶",
                        new BigDecimal("1200"), null, 0, List.of(), null));

        AuditClient.AuditRecord record = captured();
        assertEquals("catalog.item.create", record.action());
        // actionLabel 由 AuditClient.buildBody 用 AuditActions 补全（单测里 AuditClient 是 mock，不跑那段）；
        // 这里断言动作码已登记，避免前端筛选/列表里出现裸码（标签本身由 AuditActionsTest 钉住）。
        assertNotEquals(record.action(), AuditActions.labelOf(record.action()));
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertEquals("ord_catalog_item", record.resourceType());
        assertEquals(TENANT_ID, record.tenantId());
        assertEquals(STORE_ID, record.storeId());
        assertEquals("ACTIVE", created.getStatus());
        verify(catalogItemMapper).insert(created);
    }

    @Test
    void createWritesFailureAuditWithStableErrorCode() {
        ApiException ex = assertThrows(ApiException.class, () -> controller.create(
                new CatalogController.CatalogItemRequest(STORE_ID, "酒水", "PRODUCT", "青岛啤酒", "瓶",
                        null, null, 0, List.of(), null)));

        assertEquals("CATALOG_PRICE_INVALID", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("catalog.item.create", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("CATALOG_PRICE_INVALID", record.errorCode());
        verify(catalogItemMapper, never()).insert(any(CatalogItemPo.class));
    }

    @Test
    void updateWritesSucceededAuditWithStableIdempotencyKey() {
        CatalogItemPo existing = item(10L, "啤酒", "ACTIVE");
        when(catalogItemMapper.selectById(10L)).thenReturn(existing);

        controller.update(10L, new CatalogController.CatalogItemRequest(STORE_ID, "酒水", "PRODUCT", "精酿", "瓶",
                new BigDecimal("1500"), null, 1, null, null));

        AuditClient.AuditRecord record = captured();
        assertEquals("catalog.item.update", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertEquals("10", record.resourceId());
        assertEquals("catalog.item.update:10", record.idempotencyKey());
        assertEquals("精酿", record.resourceName());
    }

    @Test
    void updateWritesFailureAuditWhenItemMissing() {
        when(catalogItemMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.update(99L,
                new CatalogController.CatalogItemRequest(STORE_ID, "酒水", "PRODUCT", "精酿", "瓶",
                        new BigDecimal("1500"), null, 1, null, null)));

        assertEquals("CATALOG_ITEM_NOT_FOUND", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("catalog.item.update", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("CATALOG_ITEM_NOT_FOUND", record.errorCode());
    }

    @Test
    void disableWritesSucceededAuditAndSoftDeletes() {
        CatalogItemPo existing = item(11L, "啤酒", "ACTIVE");
        when(catalogItemMapper.selectById(11L)).thenReturn(existing);

        controller.disable(11L);

        assertEquals("INACTIVE", existing.getStatus());
        verify(catalogItemMapper).updateById(existing);
        AuditClient.AuditRecord record = captured();
        assertEquals("catalog.item.delete", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        // 软删也把删除后的状态写进 detail，便于与历史订单快照区分。
        assertEquals(true, record.detailJson().contains("\"status\":\"INACTIVE\""));
    }

    @Test
    void disableWritesFailureAuditWhenItemMissing() {
        when(catalogItemMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.disable(99L));

        assertEquals("CATALOG_ITEM_NOT_FOUND", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("catalog.item.delete", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("CATALOG_ITEM_NOT_FOUND", record.errorCode());
    }

    @Test
    void writeWithoutPermissionAuditsFailureInsteadOfSilentlySkipping() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1, List.of(), null));

        ApiException ex = assertThrows(ApiException.class, () -> controller.disable(11L));

        assertEquals("PERMISSION_DENIED", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("catalog.item.delete", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("PERMISSION_DENIED", record.errorCode());
    }

    private static CatalogItemPo item(Long id, String name, String status) {
        CatalogItemPo po = new CatalogItemPo();
        po.setId(id);
        po.setTenantId(TENANT_ID);
        po.setStoreId(STORE_ID);
        po.setName(name);
        po.setUnitPrice(new BigDecimal("1200"));
        po.setStatus(status);
        return po;
    }

    private AuditClient.AuditRecord captured() {
        ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(captor.capture());
        return captor.getValue();
    }
}

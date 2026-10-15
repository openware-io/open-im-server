package io.openware.platform.resource.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import io.openware.platform.resource.domain.resource.model.Resource;
import io.openware.platform.resource.domain.resource.repository.ResourceRepository;
import io.openware.platform.resource.domain.roomtype.repository.RoomTypeReader;
import io.openware.platform.resource.domain.occupation.repository.OccupationRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 包厢清洁状态切换的审计覆盖（2026-09 补齐）。
 *
 * <p>背景：清洁状态是「包厢能不能卖」的门禁之一（清洁中不可开台、不可预约），
 * 后台房态看板的「清洁完成」按钮此前不留痕——出现「包厢莫名不可用/莫名可用」时
 * 无法回溯是谁、什么时候切的。这里固化：真正变更才留痕（幂等 no-op 不留）、失败也留痕。
 *
 * <p>替身是**领域端口**：不再构造 MyBatis PO。
 */
class ResourceCleaningAuditTest {

    private static final long TENANT_ID = 1L;
    private static final Long STORE_ID = 3L;

    private ResourceRepository resourceRepository;
    private OccupationRepository occupationRepository;
    private RoomTypeReader roomTypeReader;
    private AuditClient auditClient;
    private ResourceStateApplicationService service;

    @BeforeEach
    void setUp() {
        resourceRepository = mock(ResourceRepository.class);
        occupationRepository = mock(OccupationRepository.class);
        roomTypeReader = mock(RoomTypeReader.class);
        auditClient = mock(AuditClient.class);
        service = new ResourceStateApplicationService(resourceRepository, occupationRepository, roomTypeReader, auditClient);
        when(occupationRepository.findActiveResourceIds(any())).thenReturn(List.of());
        when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 4L, 1));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void markingCleaningWritesSucceededAudit() {
        when(resourceRepository.findById(30L)).thenReturn(Optional.of(room(30L, Resource.CLEANING_IDLE)));

        service.setCleaning(30L, true);

        AuditClient.AuditRecord record = captured();
        assertEquals("resource.cleaning.update", record.action());
        // actionLabel 由 AuditClient.buildBody 用 AuditActions 补全（单测里 AuditClient 是 mock，不跑那段）；
        // 这里断言动作码已登记，避免前端筛选/列表里出现裸码（标签本身由 AuditActionsTest 钉住）。
        assertNotEquals(record.action(), AuditActions.labelOf(record.action()));
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertEquals("res_resource", record.resourceType());
        assertEquals("30", record.resourceId());
        assertEquals("VIP 01", record.resourceName());
        assertEquals(TENANT_ID, record.tenantId());
        assertEquals(STORE_ID, record.storeId());
        assertEquals(true, record.detailJson().contains("\"cleaning\":true"));
        assertEquals(true, record.detailJson().contains("\"state\":\"CLEANING\""));
    }

    @Test
    void finishingCleaningWritesSucceededAudit() {
        when(resourceRepository.findById(30L)).thenReturn(Optional.of(room(30L, Resource.CLEANING_IN_PROGRESS)));

        service.setCleaning(30L, false);

        AuditClient.AuditRecord record = captured();
        assertEquals("resource.cleaning.update", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertEquals(true, record.detailJson().contains("\"cleaning\":false"));
        assertEquals(true, record.detailJson().contains("\"state\":\"IDLE\""));
    }

    /** 幂等 no-op（状态本来就一致）不算一次变更：重复点「清洁完成」不该刷出一串审计噪声。 */
    @Test
    void idempotentCallWritesNoAudit() {
        when(resourceRepository.findById(30L)).thenReturn(Optional.of(room(30L, Resource.CLEANING_IN_PROGRESS)));

        service.setCleaning(30L, true);

        verify(auditClient, never()).recordAsync(any());
        verify(resourceRepository, never()).save(any(Resource.class));
    }

    @Test
    void missingResourceWritesFailureAudit() {
        when(resourceRepository.findById(99L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.setCleaning(99L, true));

        assertEquals("RESOURCE_NOT_FOUND", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("resource.cleaning.update", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("RESOURCE_NOT_FOUND", record.errorCode());
        assertEquals("99", record.resourceId());
        assertNull(record.idempotencyKey());
    }

    private static Resource room(Long id, String cleaningStatus) {
        return new Resource(id, TENANT_ID, STORE_ID, "ROOM", "V01", "VIP 01", "A 区", null, 10, null,
            Resource.STATUS_ENABLED, cleaningStatus, null, List.of(), null, null, null, null, null, null);
    }

    private AuditClient.AuditRecord captured() {
        ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(captor.capture());
        return captor.getValue();
    }
}

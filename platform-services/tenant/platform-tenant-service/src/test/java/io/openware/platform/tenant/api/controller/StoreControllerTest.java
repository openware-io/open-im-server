package io.openware.platform.tenant.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.api.controller.StoreController.UpdateStoreRequest;
import io.openware.platform.tenant.application.PermissionSnapshotProvider;
import io.openware.platform.tenant.application.StoreApplicationService;
import io.openware.platform.tenant.domain.store.model.Store;
import io.openware.platform.tenant.domain.store.repository.StoreRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 门店时区/营业日切点写接口（多时区批 1，文档 §2.1 S16 / §3.1）：
 * 读写往返、字段级可选、IANA 校验（拒绝偏移字面量）、切点格式与范围校验、
 * 权限 403、跨门店/跨租户 403、不存在 404，以及成功/失败两侧的审计留痕。
 *
 * <p><b>分层后的断言归属</b>：此前「补丁里不带未提交列」是靠捕获 {@code StorePo} 补丁并断言其余字段为 null
 * 来验证的——那是"整体保存 vs 按列更新"的实现细节。现在端口方法 {@code updateSchedule} 的签名只接受本次
 * 提交的列，所以本文件断言**传给端口的实参**；"null 列是否真的被跳过"属于适配器测试。
 */
class StoreControllerTest {

    private static final long TENANT_ID = 100L;
    private static final long STORE_ID = 100L;
    /** 写接口权限：日界决定日结/报表归日，属经营配置级，用 tenant.tenant.manage（见 StoreController 常量注释）。 */
    private static final String PERMISSION = "tenant.tenant.manage";

    private StoreRepository storeRepository;
    private AuditClient auditClient;
    private StoreController controller;

    @BeforeEach
    void setUp() {
        storeRepository = mock(StoreRepository.class);
        auditClient = mock(AuditClient.class);
        when(auditClient.recordAsync(any())).thenReturn(CompletableFuture.completedFuture(1L));
        controller = new StoreController(new StoreApplicationService(storeRepository, auditClient, null));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /** 读契约：门店列表必须带 timezone 与 businessDayCutoff（前端展示与编辑的真源）。 */
    @Test
    void listExposesTimezoneAndBusinessDayCutoff() {
        when(storeRepository.listByTenant(any(), any(), any(), any())).thenReturn(List.of(store()));
        withContext(null, PERMISSION);

        var rows = controller.list(null);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).timezone()).isEqualTo("Asia/Shanghai");
        assertThat(rows.get(0).businessDayCutoff()).isEqualTo("04:00:00");
    }

    /** 读写往返：PUT 同时改时区与切点，落库值规范化，响应与审计都带前后值。 */
    @Test
    void updateWritesTimezoneAndCutoffAndAuditsBeforeAfter() {
        when(storeRepository.findOwnerTenantId(STORE_ID)).thenReturn(Optional.of(TENANT_ID));
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(store()));
        withContext(null, PERMISSION);

        var updated = controller.update(STORE_ID, new UpdateStoreRequest("Asia/Bangkok", "02:00"));

        assertThat(updated.timezone()).isEqualTo("Asia/Bangkok");
        assertThat(updated.businessDayCutoff()).isEqualTo("02:00:00");

        // 按列更新：端口只收本次提交的两列（未提交的列根本不在签名里，无法被覆盖）
        verify(storeRepository).updateSchedule(STORE_ID, "Asia/Bangkok", "02:00:00", null, 1L);

        ArgumentCaptor<AuditClient.AuditRecord> record = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(record.capture());
        assertThat(record.getValue().action()).isEqualTo("tenant.store.update");
        assertThat(record.getValue().resourceType()).isEqualTo("tnt_store");
        assertThat(record.getValue().resourceId()).isEqualTo("100");
        assertThat(record.getValue().tenantId()).isEqualTo(TENANT_ID);
        assertThat(record.getValue().storeId()).isEqualTo(STORE_ID);
        assertThat(record.getValue().detailJson())
                .contains("\"timezone\":{\"before\":\"Asia/Shanghai\",\"after\":\"Asia/Bangkok\"}")
                .contains("\"businessDayCutoff\":{\"before\":\"04:00:00\",\"after\":\"02:00:00\"}");
    }

    /** 字段级可选：只传时区时切点保持原值，且下发的补丁里不带切点。 */
    @Test
    void updateKeepsUntouchedFieldWhenOnlyTimezoneProvided() {
        when(storeRepository.findOwnerTenantId(STORE_ID)).thenReturn(Optional.of(TENANT_ID));
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(store()));
        withContext(null, PERMISSION);

        var updated = controller.update(STORE_ID, new UpdateStoreRequest("America/New_York", null));

        assertThat(updated.timezone()).isEqualTo("America/New_York");
        assertThat(updated.businessDayCutoff()).isEqualTo("04:00:00");
        // 未提交切点 → 实参为 null，端口据此不写该列
        verify(storeRepository).updateSchedule(STORE_ID, "America/New_York", null, null, 1L);
    }

    /** 切点秒级精度按分钟落库（与列表出参的 HH:mm:ss 一致）。 */
    @Test
    void updateNormalizesSecondPrecisionCutoffToMinutes() {
        when(storeRepository.findOwnerTenantId(STORE_ID)).thenReturn(Optional.of(TENANT_ID));
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(store()));
        withContext(null, PERMISSION);

        var updated = controller.update(STORE_ID, new UpdateStoreRequest(null, "02:00:30"));

        assertThat(updated.businessDayCutoff()).isEqualTo("02:00:00");
    }

    /** 业态只能写入 ACTIVE 字典值，并与时区/切点更新共用同一字段级补丁。 */
    @Test
    void updateWritesActiveBusinessTypeAndAuditsBeforeAfter() {
        when(storeRepository.findActiveBusinessTypeCode("HOTEL")).thenReturn("HOTEL");
        when(storeRepository.findOwnerTenantId(STORE_ID)).thenReturn(Optional.of(TENANT_ID));
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(store()));
        withContext(null, PERMISSION);

        var updated = controller.update(STORE_ID, new UpdateStoreRequest(null, null, "hotel"));

        assertThat(updated.businessType()).isEqualTo("HOTEL");
        assertThat(updated.timezone()).isEqualTo("Asia/Shanghai");
        // 三列同属一次按列更新（未提交的列以 null 传入，端口据此跳过）；
        // 端口刻意不提供整体保存方法，所以"未提交列被覆盖"这种退化在编译期就不可能发生。
        verify(storeRepository).updateSchedule(STORE_ID, null, null, "HOTEL", 1L);
        ArgumentCaptor<AuditClient.AuditRecord> record = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(record.capture());
        assertThat(record.getValue().detailJson())
                .contains("\"businessType\":{\"before\":\"KTV\",\"after\":\"HOTEL\"}");
    }

    /** 非 ACTIVE 业态直接 400，不能因门店租户边界校验而产生写入。 */
    @Test
    void updateRejectsInactiveBusinessType() {
        when(storeRepository.findActiveBusinessTypeCode("HOTEL")).thenReturn(null);
        withContext(null, PERMISSION);

        assertThatThrownBy(() -> controller.update(STORE_ID, new UpdateStoreRequest(null, null, "hotel")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", 400)
                .hasFieldOrPropertyWithValue("code", "BUSINESS_TYPE_INVALID");
        verify(storeRepository, never()).findOwnerTenantId(any());
        verify(storeRepository, never()).findById(any());
        verify(storeRepository, never()).updateSchedule(anyLong(), any(), any(), any(), any());
    }

    /** 业态改变后必须立即清理按门店业态过滤的权限快照。 */
    @Test
    void updateBusinessTypeEvictsPermissionSnapshots() {
        PermissionSnapshotProvider snapshots = mock(PermissionSnapshotProvider.class);
        controller = new StoreController(new StoreApplicationService(storeRepository, auditClient, snapshots));
        when(storeRepository.findActiveBusinessTypeCode("SPA")).thenReturn("SPA");
        when(storeRepository.findOwnerTenantId(STORE_ID)).thenReturn(Optional.of(TENANT_ID));
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(store()));
        withContext(null, PERMISSION);

        controller.update(STORE_ID, new UpdateStoreRequest(null, null, "SPA"));

        verify(snapshots).evictAll();
    }

    /** 时区必须是 IANA id：`+08:00` 这类偏移字面量 400，且不落库。 */
    @Test
    void updateRejectsOffsetLiteralTimezone() {
        withContext(null, PERMISSION);

        assertThatThrownBy(() -> controller.update(STORE_ID, new UpdateStoreRequest("+08:00", null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", 400)
                .hasFieldOrPropertyWithValue("code", "TIMEZONE_INVALID");
        verify(storeRepository, never()).updateSchedule(anyLong(), any(), any(), any(), any());

        ArgumentCaptor<AuditClient.AuditRecord> record = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(record.capture());
        assertThat(record.getValue().result()).isEqualTo(AuditClient.AuditRecord.RESULT_FAILURE);
        assertThat(record.getValue().errorCode()).isEqualTo("TIMEZONE_INVALID");
        assertThat(record.getValue().detailJson()).contains("\"requestedTimezone\":\"+08:00\"");
    }

    /** 切点格式与范围：`4:00` 格式非法、`13:00` 超范围，两者都 400 且不落库。 */
    @Test
    void updateRejectsInvalidAndOutOfRangeCutoff() {
        withContext(null, PERMISSION);

        assertThatThrownBy(() -> controller.update(STORE_ID, new UpdateStoreRequest(null, "4:00")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "BUSINESS_DAY_CUTOFF_INVALID");
        assertThatThrownBy(() -> controller.update(STORE_ID, new UpdateStoreRequest(null, "13:00")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "BUSINESS_DAY_CUTOFF_OUT_OF_RANGE");
        verify(storeRepository, never()).findOwnerTenantId(any());
        verify(storeRepository, never()).updateSchedule(anyLong(), any(), any(), any(), any());
    }

    /** 空请求体 / 两个字段都不传：400，且不触达数据库。 */
    @Test
    void updateRejectsRequestWithoutAnyField() {
        withContext(null, PERMISSION);

        assertThatThrownBy(() -> controller.update(STORE_ID, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "STORE_UPDATE_EMPTY");
        assertThatThrownBy(() -> controller.update(STORE_ID, new UpdateStoreRequest(null, null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "STORE_UPDATE_EMPTY");
        verify(storeRepository, never()).findOwnerTenantId(any());
        verify(storeRepository, never()).updateSchedule(anyLong(), any(), any(), any(), any());
    }

    /** 跨租户：门店属于别的租户 → 403 TENANT_SCOPE_DENIED（不能被静默降级成 404）。 */
    @Test
    void updateRejectsStoreOfAnotherTenant() {
        when(storeRepository.findOwnerTenantId(STORE_ID)).thenReturn(Optional.of(200L));
        withContext(null, PERMISSION);

        assertThatThrownBy(() -> controller.update(STORE_ID, new UpdateStoreRequest("Asia/Bangkok", null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", 403)
                .hasFieldOrPropertyWithValue("code", "TENANT_SCOPE_DENIED");
        verify(storeRepository, never()).updateSchedule(anyLong(), any(), any(), any(), any());
    }

    /** 跨门店：签名上下文已锁定门店 A，路径要改门店 B → 403，且不查库。 */
    @Test
    void updateRejectsStoreOutsideSignedContextStore() {
        withContext(999L, PERMISSION);

        assertThatThrownBy(() -> controller.update(STORE_ID, new UpdateStoreRequest("Asia/Bangkok", null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", 403)
                .hasFieldOrPropertyWithValue("code", "STORE_SCOPE_DENIED");
        verify(storeRepository, never()).findOwnerTenantId(any());
        verify(storeRepository, never()).updateSchedule(anyLong(), any(), any(), any(), any());
    }

    /** 门店不存在：404，且不写库。 */
    @Test
    void updateRejectsUnknownStore() {
        when(storeRepository.findOwnerTenantId(404L)).thenReturn(Optional.empty());
        withContext(null, PERMISSION);

        assertThatThrownBy(() -> controller.update(404L, new UpdateStoreRequest("Asia/Bangkok", null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", 404)
                .hasFieldOrPropertyWithValue("code", "STORE_NOT_FOUND");
        verify(storeRepository, never()).updateSchedule(anyLong(), any(), any(), any(), any());
    }

    /** 同租户但租户拦截器读不到该行（例如刚被改成别的租户）：同样 404，不写库。 */
    @Test
    void updateRejectsStoreInvisibleToTenantScopedSelect() {
        when(storeRepository.findOwnerTenantId(STORE_ID)).thenReturn(Optional.of(TENANT_ID));
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.empty());
        withContext(null, PERMISSION);

        assertThatThrownBy(() -> controller.update(STORE_ID, new UpdateStoreRequest("Asia/Bangkok", null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "STORE_NOT_FOUND");
        verify(storeRepository, never()).updateSchedule(anyLong(), any(), any(), any(), any());
    }

    /** 权限 403 优先于校验：缺 tenant.tenant.manage 时即使入参非法也报 PERMISSION_DENIED，且留痕。 */
    @Test
    void updateRequiresStoreManagePermission() {
        withContext(null);

        assertThatThrownBy(() -> controller.update(STORE_ID, new UpdateStoreRequest("+08:00", null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", 403)
                .hasFieldOrPropertyWithValue("code", "PERMISSION_DENIED");
        verify(storeRepository, never()).updateSchedule(anyLong(), any(), any(), any(), any());

        ArgumentCaptor<AuditClient.AuditRecord> record = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(record.capture());
        assertThat(record.getValue().result()).isEqualTo(AuditClient.AuditRecord.RESULT_FAILURE);
        assertThat(record.getValue().errorCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(record.getValue().tenantId()).isEqualTo(TENANT_ID);
    }

    /** 无租户上下文：401，且不触达数据库。 */
    @Test
    void updateRequiresTenantContext() {
        TenantContextHolder.clear();

        assertThatThrownBy(() -> controller.update(STORE_ID, new UpdateStoreRequest("Asia/Bangkok", null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", 401)
                .hasFieldOrPropertyWithValue("code", "SAAS_CONTEXT_REQUIRED");
        verify(storeRepository, never()).findOwnerTenantId(any());
    }

    private void withContext(Long contextStoreId, String... permissions) {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, contextStoreId, 1L, 1, List.of(permissions)));
    }

    private static Store store() {
        return new Store(STORE_ID, TENANT_ID, 1L, "a380-ktv-001", "A380 KTV 旗舰店", "KTV", "CN", null,
            "Asia/Shanghai", "CNY", "zh-CN", null, "04:00:00", Store.STATUS_ACTIVE, null);
    }
}

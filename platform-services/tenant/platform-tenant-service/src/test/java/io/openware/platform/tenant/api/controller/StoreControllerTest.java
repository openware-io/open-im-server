package io.openware.platform.tenant.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.api.controller.StoreController.UpdateStoreRequest;
import io.openware.platform.tenant.application.StoreApplicationService;
import io.openware.platform.tenant.infra.persistence.mapper.StoreMapper;
import io.openware.platform.tenant.infra.persistence.po.StorePo;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 门店时区/营业日切点写接口（多时区批 1，文档 §2.1 S16 / §3.1）：
 * 读写往返、字段级可选、IANA 校验（拒绝偏移字面量）、切点格式与范围校验、
 * 权限 403、跨门店/跨租户 403、不存在 404，以及成功/失败两侧的审计留痕。
 */
class StoreControllerTest {

    private static final long TENANT_ID = 100L;
    private static final long STORE_ID = 100L;
    /** 写接口权限：日界决定日结/报表归日，属经营配置级，用 tenant.tenant.manage（见 StoreController 常量注释）。 */
    private static final String PERMISSION = "tenant.tenant.manage";

    private StoreMapper storeMapper;
    private AuditClient auditClient;
    private StoreController controller;

    @BeforeEach
    void setUp() {
        storeMapper = mock(StoreMapper.class);
        auditClient = mock(AuditClient.class);
        when(auditClient.recordAsync(any())).thenReturn(CompletableFuture.completedFuture(1L));
        controller = new StoreController(storeMapper, new StoreApplicationService(storeMapper), auditClient);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /** 读契约：门店列表必须带 timezone 与 businessDayCutoff（前端展示与编辑的真源）。 */
    @Test
    void listExposesTimezoneAndBusinessDayCutoff() {
        StorePo store = store();
        when(storeMapper.selectList(any())).thenReturn(List.of(store));
        withContext(null, PERMISSION);

        List<StorePo> rows = controller.list(null);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(rows.get(0).getBusinessDayCutoff()).isEqualTo("04:00:00");
    }

    /** 读写往返：PUT 同时改时区与切点，落库值规范化，响应与审计都带前后值。 */
    @Test
    void updateWritesTimezoneAndCutoffAndAuditsBeforeAfter() {
        StorePo store = store();
        when(storeMapper.selectTenantIdById(STORE_ID)).thenReturn(TENANT_ID);
        when(storeMapper.selectById(STORE_ID)).thenReturn(store);
        withContext(null, PERMISSION);

        StorePo updated = controller.update(STORE_ID, new UpdateStoreRequest("Asia/Bangkok", "02:00"));

        assertThat(updated.getTimezone()).isEqualTo("Asia/Bangkok");
        assertThat(updated.getBusinessDayCutoff()).isEqualTo("02:00:00");
        assertThat(updated.getUpdatedAt()).isNotNull();
        assertThat(updated.getUpdatedBy()).isEqualTo(1L);

        ArgumentCaptor<StorePo> patch = ArgumentCaptor.forClass(StorePo.class);
        verify(storeMapper).updateById(patch.capture());
        assertThat(patch.getValue().getId()).isEqualTo(STORE_ID);
        assertThat(patch.getValue().getTimezone()).isEqualTo("Asia/Bangkok");
        assertThat(patch.getValue().getBusinessDayCutoff()).isEqualTo("02:00:00");
        // 字段级更新：未传的字段必须保持 null，避免把门店名/状态等无关列一起写回
        assertThat(patch.getValue().getName()).isNull();
        assertThat(patch.getValue().getStatus()).isNull();
        assertThat(patch.getValue().getDefaultCurrency()).isNull();

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

    /** 字段级可选：只传时区时切点保持原值，且补丁里不带切点。 */
    @Test
    void updateKeepsUntouchedFieldWhenOnlyTimezoneProvided() {
        StorePo store = store();
        when(storeMapper.selectTenantIdById(STORE_ID)).thenReturn(TENANT_ID);
        when(storeMapper.selectById(STORE_ID)).thenReturn(store);
        withContext(null, PERMISSION);

        StorePo updated = controller.update(STORE_ID, new UpdateStoreRequest("America/New_York", null));

        assertThat(updated.getTimezone()).isEqualTo("America/New_York");
        assertThat(updated.getBusinessDayCutoff()).isEqualTo("04:00:00");
        ArgumentCaptor<StorePo> patch = ArgumentCaptor.forClass(StorePo.class);
        verify(storeMapper).updateById(patch.capture());
        assertThat(patch.getValue().getBusinessDayCutoff()).isNull();
    }

    /** 切点秒级精度按分钟落库（与列表出参的 HH:mm:ss 一致）。 */
    @Test
    void updateNormalizesSecondPrecisionCutoffToMinutes() {
        when(storeMapper.selectTenantIdById(STORE_ID)).thenReturn(TENANT_ID);
        when(storeMapper.selectById(STORE_ID)).thenReturn(store());
        withContext(null, PERMISSION);

        StorePo updated = controller.update(STORE_ID, new UpdateStoreRequest(null, "02:00:30"));

        assertThat(updated.getBusinessDayCutoff()).isEqualTo("02:00:00");
    }

    /** 时区必须是 IANA id：`+08:00` 这类偏移字面量 400，且不落库。 */
    @Test
    void updateRejectsOffsetLiteralTimezone() {
        withContext(null, PERMISSION);

        assertThatThrownBy(() -> controller.update(STORE_ID, new UpdateStoreRequest("+08:00", null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", 400)
                .hasFieldOrPropertyWithValue("code", "TIMEZONE_INVALID");
        verify(storeMapper, never()).updateById(any(StorePo.class));

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
        verify(storeMapper, never()).selectTenantIdById(any());
        verify(storeMapper, never()).updateById(any(StorePo.class));
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
        verify(storeMapper, never()).selectTenantIdById(any());
        verify(storeMapper, never()).updateById(any(StorePo.class));
    }

    /** 跨租户：门店属于别的租户 → 403 TENANT_SCOPE_DENIED（不能被静默降级成 404）。 */
    @Test
    void updateRejectsStoreOfAnotherTenant() {
        when(storeMapper.selectTenantIdById(STORE_ID)).thenReturn(200L);
        withContext(null, PERMISSION);

        assertThatThrownBy(() -> controller.update(STORE_ID, new UpdateStoreRequest("Asia/Bangkok", null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", 403)
                .hasFieldOrPropertyWithValue("code", "TENANT_SCOPE_DENIED");
        verify(storeMapper, never()).updateById(any(StorePo.class));
    }

    /** 跨门店：签名上下文已锁定门店 A，路径要改门店 B → 403，且不查库。 */
    @Test
    void updateRejectsStoreOutsideSignedContextStore() {
        withContext(999L, PERMISSION);

        assertThatThrownBy(() -> controller.update(STORE_ID, new UpdateStoreRequest("Asia/Bangkok", null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", 403)
                .hasFieldOrPropertyWithValue("code", "STORE_SCOPE_DENIED");
        verify(storeMapper, never()).selectTenantIdById(any());
        verify(storeMapper, never()).updateById(any(StorePo.class));
    }

    /** 门店不存在：404，且不写库。 */
    @Test
    void updateRejectsUnknownStore() {
        when(storeMapper.selectTenantIdById(404L)).thenReturn(null);
        withContext(null, PERMISSION);

        assertThatThrownBy(() -> controller.update(404L, new UpdateStoreRequest("Asia/Bangkok", null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", 404)
                .hasFieldOrPropertyWithValue("code", "STORE_NOT_FOUND");
        verify(storeMapper, never()).updateById(any(StorePo.class));
    }

    /** 同租户但租户拦截器读不到该行（例如刚被改成别的租户）：同样 404，不写库。 */
    @Test
    void updateRejectsStoreInvisibleToTenantScopedSelect() {
        when(storeMapper.selectTenantIdById(STORE_ID)).thenReturn(TENANT_ID);
        when(storeMapper.selectById(STORE_ID)).thenReturn(null);
        withContext(null, PERMISSION);

        assertThatThrownBy(() -> controller.update(STORE_ID, new UpdateStoreRequest("Asia/Bangkok", null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "STORE_NOT_FOUND");
        verify(storeMapper, never()).updateById(any(StorePo.class));
    }

    /** 权限 403 优先于校验：缺 tenant.tenant.manage 时即使入参非法也报 PERMISSION_DENIED，且留痕。 */
    @Test
    void updateRequiresStoreManagePermission() {
        withContext(null);

        assertThatThrownBy(() -> controller.update(STORE_ID, new UpdateStoreRequest("+08:00", null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", 403)
                .hasFieldOrPropertyWithValue("code", "PERMISSION_DENIED");
        verify(storeMapper, never()).updateById(any(StorePo.class));

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
        verify(storeMapper, never()).selectTenantIdById(any());
    }

    private void withContext(Long contextStoreId, String... permissions) {
        TenantContextHolder.set(new TenantContext(TENANT_ID, null, contextStoreId, 1L, 1, List.of(permissions)));
    }

    private static StorePo store() {
        StorePo store = new StorePo();
        store.setId(STORE_ID);
        store.setTenantId(TENANT_ID);
        store.setCode("a380-ktv-001");
        store.setName("A380 KTV 旗舰店");
        store.setBusinessType("KTV");
        store.setCountryCode("CN");
        store.setTimezone("Asia/Shanghai");
        store.setDefaultCurrency("CNY");
        store.setLocale("zh-CN");
        store.setBusinessDayCutoff("04:00:00");
        store.setStatus("ACTIVE");
        return store;
    }
}

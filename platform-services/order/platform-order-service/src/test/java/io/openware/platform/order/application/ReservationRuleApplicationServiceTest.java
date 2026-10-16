package io.openware.platform.order.application;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.order.api.dto.ReservationRuleConfigView;
import io.openware.platform.order.domain.reservation.model.ReservationRuleConfig;
import io.openware.platform.order.domain.reservation.repository.ReservationRuleConfigRepository;
import io.openware.platform.order.domain.reservation.repository.ReservationRuleConfigRepository.AuditSnapshot;
import io.openware.platform.order.domain.reservation.repository.ReservationRuleConfigRepository.Persisted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预约规则应用的解析顺序与保存语义（门店覆盖 &gt; 业态默认 &gt; 租户默认）。
 *
 * <p>改造后本类只依赖领域端口 {@link ReservationRuleConfigRepository}：
 * 断言的是「端口被怎么调用」与「CAS 冲突必须 409」，SQL 形状（版本条件与列集合）钉在
 * {@code ReservationRuleConfigRepositoryAdapterTest}。
 */
class ReservationRuleApplicationServiceTest {

    private static final Long TENANT_ID = 7L;

    private ReservationRuleConfigRepository repository;
    private ReservationRuleApplicationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReservationRuleConfigRepository.class);
        service = new ReservationRuleApplicationService(repository, mock(AuditClient.class));
        when(repository.findActive(any(), anyString(), any())).thenReturn(Optional.empty());
    }

    @Test
    void resolvePrefersStoreOverrideThenBusinessThenTenantThenDefaults() {
        ReservationRuleConfig store = rule(1L, "KTV", 1001L, 30, 10, 20, 3);
        when(repository.findActive(TENANT_ID, "KTV", 1001L)).thenReturn(Optional.of(persisted(store)));

        ReservationRuleApplicationService.RuleView view = service.resolve(TENANT_ID, "ktv", 1001L);

        assertEquals(30, view.advanceMinutes());
        assertEquals("STORE", view.source(), "门店覆盖优先，且业态大小写归一");

        when(repository.findActive(TENANT_ID, "KTV", 1001L)).thenReturn(Optional.empty());
        when(repository.findActive(TENANT_ID, "KTV", 0L)).thenReturn(Optional.of(persisted(rule(2L, "KTV", 0L, 15, 5, 10, 1))));

        assertEquals("BUSINESS", service.resolve(TENANT_ID, "KTV", 1001L).source());

        when(repository.findActive(TENANT_ID, "KTV", 0L)).thenReturn(Optional.empty());
        when(repository.findActive(TENANT_ID, "", 0L)).thenReturn(Optional.of(persisted(rule(3L, "", 0L, 5, 0, 0, 0))));

        assertEquals("TENANT", service.resolve(TENANT_ID, "KTV", 1001L).source());

        when(repository.findActive(TENANT_ID, "", 0L)).thenReturn(Optional.empty());

        ReservationRuleApplicationService.RuleView defaults = service.resolve(TENANT_ID, "KTV", 1001L);
        assertEquals(0, defaults.advanceMinutes());
        assertEquals("DEFAULT", defaults.source(), "三档都读不到才回退代码缺省");
    }

    /** 缺少租户上下文：直接拒绝，不查库。 */
    @Test
    void resolveRequiresTenantContext() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.resolve(null, "KTV", 1L));

        assertEquals("SAAS_CONTEXT_REQUIRED", ex.getCode());
        verify(repository, never()).findActive(any(), anyString(), any());
    }

    /** 幂等重试：同幂等键命中既有行时原样返回，不再插一行、也不判版本。 */
    @Test
    void saveReturnsExistingRowOnIdempotentRetry() {
        ReservationRuleConfig existing = rule(11L, "KTV", 0L, 30, 10, 20, 3);
        when(repository.findByIdempotencyKey(TENANT_ID, "idem-1")).thenReturn(Optional.of(persisted(existing)));

        ReservationRuleConfigView saved = service.save(TENANT_ID, new ReservationRuleApplicationService.SaveCommand(
                "KTV", 0L, 30, 10, 20, null, "idem-1"));

        assertEquals(11L, saved.id());
        assertEquals(3, saved.version());
        verify(repository, never()).insert(any());
        verify(repository, never()).updateWindowsWithVersion(any(), anyInt());
    }

    /** 没有既有行/幂等命中时新增：落库聚合是 draft（版本 0、状态 ACTIVE），响应带记账值。 */
    @Test
    void saveInsertsDraftWhenAbsent() {
        when(repository.findByIdempotencyKey(TENANT_ID, "idem-2")).thenReturn(Optional.empty());
        when(repository.insert(any())).thenAnswer(invocation -> {
            ReservationRuleConfig draft = invocation.getArgument(0);
            return new Persisted<>(draft.withId(12L),
                    new AuditSnapshot(null, LocalDateTime.of(2026, 9, 18, 9, 0), null, LocalDateTime.of(2026, 9, 18, 9, 0)));
        });

        ReservationRuleConfigView saved = service.save(TENANT_ID, new ReservationRuleApplicationService.SaveCommand(
                "KTV", null, 30, 10, 20, null, "idem-2"));

        ArgumentCaptor<ReservationRuleConfig> captor = ArgumentCaptor.forClass(ReservationRuleConfig.class);
        verify(repository).insert(captor.capture());
        assertEquals(0L, captor.getValue().storeId(), "storeId 为空按 0（租户/业态默认档）落库");
        assertEquals(0, captor.getValue().currentVersion());
        assertEquals(ReservationRuleConfig.STATUS_ACTIVE, captor.getValue().status());
        assertEquals(12L, saved.id());
        assertEquals(LocalDateTime.of(2026, 9, 18, 9, 0), saved.createdAt());
    }

    /** 覆盖写：条件更新影响 0 行 = 期间已被他人写入 → 409 版本冲突（绝不静默覆盖）。 */
    @Test
    void saveRaisesConflictWhenConditionalUpdateAffectsNoRow() {
        when(repository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(repository.findActive(TENANT_ID, "KTV", 0L)).thenReturn(Optional.of(persisted(rule(11L, "KTV", 0L, 30, 10, 20, 3))));
        when(repository.updateWindowsWithVersion(any(), anyInt())).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> service.save(TENANT_ID,
                new ReservationRuleApplicationService.SaveCommand("KTV", 0L, 60, 30, 40, null, null)));

        assertEquals(409, ex.getStatus());
        assertEquals("RESERVATION_RULE_VERSION_CONFLICT", ex.getCode());
        verify(repository).updateWindowsWithVersion(any(), eq(3));
    }

    /** 调用方带来的期望版本与库里不一致：快速失败（真正的判定点仍是那条 CAS）。 */
    @Test
    void saveRejectsStaleExpectedVersionBeforeCas() {
        when(repository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(repository.findActive(TENANT_ID, "KTV", 0L)).thenReturn(Optional.of(persisted(rule(11L, "KTV", 0L, 30, 10, 20, 3))));

        ApiException ex = assertThrows(ApiException.class, () -> service.save(TENANT_ID,
                new ReservationRuleApplicationService.SaveCommand("KTV", 0L, 60, 30, 40, 2, null)));

        assertEquals("RESERVATION_RULE_VERSION_CONFLICT", ex.getCode());
        verify(repository, never()).updateWindowsWithVersion(any(), anyInt());
    }

    /** 门店覆盖必须带业态（否则「门店覆盖」会落到别人的业态上）。 */
    @Test
    void saveRequiresBusinessTypeForStoreOverride() {
        ApiException ex = assertThrows(ApiException.class, () -> service.save(TENANT_ID,
                new ReservationRuleApplicationService.SaveCommand(null, 1001L, 30, 10, 20, null, null)));

        assertEquals(400, ex.getStatus());
        assertEquals("BUSINESS_TYPE_REQUIRED", ex.getCode());
        verify(repository, never()).insert(any());
    }

    /** 提前预约窗口：不足最小提前量 → 422（读取口径走 resolve → 端口）。 */
    @Test
    void validateCreateEnforcesAdvanceWindow() {
        when(repository.findActive(TENANT_ID, "KTV", 0L)).thenReturn(Optional.of(persisted(rule(11L, "KTV", 0L, 60, 0, 0, 0))));

        ApiException ex = assertThrows(ApiException.class, () -> service.validateCreate(TENANT_ID, "KTV", 0L,
                LocalDateTime.now().plusMinutes(10)));

        assertEquals(422, ex.getStatus());
        assertEquals("RESERVATION_ADVANCE_WINDOW", ex.getCode());
    }

    // ---------------------------------------------------------------- fixtures

    private static ReservationRuleConfig rule(Long id, String businessType, Long storeId, Integer advance,
                                              Integer cancel, Integer reschedule, Integer version) {
        return new ReservationRuleConfig(id, TENANT_ID, businessType, storeId, advance, cancel, reschedule,
                version, null, ReservationRuleConfig.STATUS_ACTIVE);
    }

    private static Persisted<ReservationRuleConfig> persisted(ReservationRuleConfig rule) {
        return new Persisted<>(rule, new AuditSnapshot(1L, LocalDateTime.of(2026, 9, 18, 9, 0),
                1L, LocalDateTime.of(2026, 9, 18, 9, 0)));
    }
}

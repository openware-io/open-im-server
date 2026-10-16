package io.openware.platform.order.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.order.domain.order.model.VoidRuleConfig;
import io.openware.platform.order.domain.order.repository.AuditSnapshot;
import io.openware.platform.order.domain.order.repository.Persisted;
import io.openware.platform.order.domain.order.repository.VoidRuleConfigRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 作废审批规则解析顺序与保存的版本保护。
 *
 * <p>端口化后：定位口径收进 {@code VoidRuleConfigRepository.findActive/findByIdempotencyKey}
 * （原来断言的是 Lambda 条件对象，现在断言的是端口调用本身）；保存返回
 * {@code Persisted<VoidRuleConfig>}（响应要 {@code createdAt/updatedAt}，它们是记账列、不进领域模型）。
 */
class VoidRuleApplicationServiceTest {
    private final VoidRuleConfigRepository repository = mock(VoidRuleConfigRepository.class);
    private final VoidRuleApplicationService service =
            new VoidRuleApplicationService(repository, AuditClient.disabled());

    @Test
    void resolvesStoreBeforeBusinessAndTenant() {
        VoidRuleConfig store = row(3L, "KTV", true, 7);
        VoidRuleConfig business = row(0L, "KTV", false, 2);
        VoidRuleConfig tenant = row(0L, "", true, 1);
        when(repository.findActive(any(), any(), any()))
                .thenReturn(Optional.of(persisted(store)), Optional.of(persisted(business)),
                        Optional.of(persisted(tenant)));

        VoidRuleApplicationService.RuleView view = service.resolve(9L, "ktv", 3L);

        assertTrue(view.requireApproval());
        assertEquals("STORE", view.source());
        // 门店覆盖先命中：只查一次（解析顺序不变）。
        verify(repository, times(1)).findActive(any(), any(), any());
    }

    @Test
    void defaultIsFailOpenWithoutExplicitRule() {
        when(repository.findActive(any(), any(), any())).thenReturn(Optional.empty());

        VoidRuleApplicationService.RuleView view = service.resolve(9L, "KTV", 3L);

        assertFalse(view.requireApproval());
        assertEquals("DEFAULT", view.source());
    }

    @Test
    void saveCreatesScopedRule() {
        when(repository.findActive(any(), any(), any())).thenReturn(Optional.empty());
        when(repository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(repository.insert(any(VoidRuleConfig.class))).thenAnswer(invocation -> {
            VoidRuleConfig row = invocation.getArgument(0);
            // 落库回填主键：端口返回带 id 的新聚合（领域模型不可变）。
            return persisted(new VoidRuleConfig(11L, row.tenantId(), row.businessType(), row.storeId(),
                    row.requireApproval(), row.version(), row.idempotencyKey(), row.status()));
        });

        Persisted<VoidRuleConfig> saved = service.save(9L,
                new VoidRuleApplicationService.SaveCommand("KTV", 3L, true, null, "void-rule-1"));

        assertTrue(saved.aggregate().requireApproval());
        assertEquals(0, saved.aggregate().version());
        assertEquals(11L, saved.aggregate().id(), "落库主键必须回填到返回的聚合上");
        verify(repository).insert(any(VoidRuleConfig.class));
    }

    @Test
    void rejectsStoreScopeWithoutBusinessType() {
        ApiException ex = assertThrows(ApiException.class, () -> service.save(9L,
                new VoidRuleApplicationService.SaveCommand(null, 3L, true, null, "void-rule-2")));
        assertEquals("BUSINESS_TYPE_REQUIRED", ex.getCode());
    }

    private static VoidRuleConfig row(long storeId, String businessType, boolean required, int version) {
        return new VoidRuleConfig(null, 9L, businessType, storeId, required, version, null,
                VoidRuleConfig.STATUS_ACTIVE);
    }

    /** 读路径的落库行 + 记账快照（本用例只关心业务字段，快照给 null 值即可）。 */
    private static Persisted<VoidRuleConfig> persisted(VoidRuleConfig row) {
        return new Persisted<>(row, new AuditSnapshot(null, LocalDateTime.now(), null, LocalDateTime.now()));
    }
}

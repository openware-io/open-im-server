package io.openware.platform.order.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.order.infra.persistence.mapper.OrdVoidRuleConfigMapper;
import io.openware.platform.order.infra.persistence.po.OrdVoidRuleConfigPo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VoidRuleApplicationServiceTest {
    private final OrdVoidRuleConfigMapper mapper = mock(OrdVoidRuleConfigMapper.class);
    private final VoidRuleApplicationService service = new VoidRuleApplicationService(mapper, AuditClient.disabled());

    @Test
    void resolvesStoreBeforeBusinessAndTenant() {
        OrdVoidRuleConfigPo store = row(3L, "KTV", true, 7);
        OrdVoidRuleConfigPo business = row(0L, "KTV", false, 2);
        OrdVoidRuleConfigPo tenant = row(0L, "", true, 1);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(store, business, tenant);

        VoidRuleApplicationService.RuleView view = service.resolve(9L, "ktv", 3L);

        assertTrue(view.requireApproval());
        assertEquals("STORE", view.source());
        verify(mapper, times(1)).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void defaultIsFailOpenWithoutExplicitRule() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        VoidRuleApplicationService.RuleView view = service.resolve(9L, "KTV", 3L);

        assertFalse(view.requireApproval());
        assertEquals("DEFAULT", view.source());
    }

    @Test
    void saveCreatesScopedRule() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null, null);
        when(mapper.insert(any(OrdVoidRuleConfigPo.class))).thenAnswer(invocation -> {
            OrdVoidRuleConfigPo row = invocation.getArgument(0);
            row.setId(11L);
            return 1;
        });

        OrdVoidRuleConfigPo saved = service.save(9L,
                new VoidRuleApplicationService.SaveCommand("KTV", 3L, true, null, "void-rule-1"));

        assertTrue(saved.getRequireApproval());
        assertEquals(0, saved.getVersion());
        verify(mapper).insert(any(OrdVoidRuleConfigPo.class));
    }

    @Test
    void rejectsStoreScopeWithoutBusinessType() {
        ApiException ex = assertThrows(ApiException.class, () -> service.save(9L,
                new VoidRuleApplicationService.SaveCommand(null, 3L, true, null, "void-rule-2")));
        assertEquals("BUSINESS_TYPE_REQUIRED", ex.getCode());
    }

    private static OrdVoidRuleConfigPo row(long storeId, String businessType, boolean required, int version) {
        OrdVoidRuleConfigPo row = new OrdVoidRuleConfigPo();
        row.setTenantId(9L);
        row.setStoreId(storeId);
        row.setBusinessType(businessType);
        row.setRequireApproval(required);
        row.setVersion(version);
        row.setStatus("ACTIVE");
        return row;
    }
}

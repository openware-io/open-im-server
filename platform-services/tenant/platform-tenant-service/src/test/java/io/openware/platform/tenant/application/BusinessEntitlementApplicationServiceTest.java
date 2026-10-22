package io.openware.platform.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.infra.persistence.mapper.BusinessEntitlementMapper;
import io.openware.platform.tenant.infra.persistence.po.BusinessEntitlementPo;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BusinessEntitlementApplicationServiceTest {

    @Mock
    private BusinessEntitlementMapper mapper;

    private BusinessEntitlementApplicationService service;

    @BeforeEach
    void setUp() {
        service = new BusinessEntitlementApplicationService(mapper);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void listRequiresTenantScopeAndViewPermission() {
        TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1,
                List.of("tenant.overview.view"), "TENANT", null, null));
        BusinessEntitlementPo hotel = new BusinessEntitlementPo();
        hotel.setBusinessType("HOTEL");
        when(mapper.selectList(any())).thenReturn(List.of(hotel));

        assertThat(service.list()).extracting(BusinessEntitlementPo::getBusinessType).containsExactly("HOTEL");
    }

    @Test
    void enableRejectsMissingActionPermission() {
        TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1,
                List.of("tenant.overview.view"), "TENANT", null, null));

        assertThatThrownBy(() -> service.enable("HOTEL"))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(403);
    }

    @Test
    void enableUsesActionPermission() {
        TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1,
                List.of("tenant.business.manage"), "TENANT", null, null));
        when(mapper.selectOne(any())).thenReturn(null);

        BusinessEntitlementPo created = service.enable("hotel");

        assertThat(created.getBusinessType()).isEqualTo("HOTEL");
        assertThat(created.getTenantId()).isEqualTo(100L);
    }
}

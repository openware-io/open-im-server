package io.openware.platform.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.domain.entitlement.repository.BusinessEntitlementRepository;
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
    private BusinessEntitlementRepository repository;

    private BusinessEntitlementApplicationService service;

    @BeforeEach
    void setUp() {
        service = new BusinessEntitlementApplicationService(repository);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void listRequiresTenantScopeAndViewPermission() {
        TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1,
                List.of("tenant.overview.view"), "TENANT", null, null));
        BusinessEntitlementRepository.BusinessEntitlement hotel =
                new BusinessEntitlementRepository.BusinessEntitlement(1L, "HOTEL", "ACTIVE", "MANUAL");
        when(repository.findActive(100L)).thenReturn(List.of(hotel));

        assertThat(service.list()).extracting(BusinessEntitlementApplicationService.BusinessEntitlementView::businessType).containsExactly("HOTEL");
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
        when(repository.find(100L, "HOTEL"))
                .thenReturn(new BusinessEntitlementRepository.BusinessEntitlement(2L, "HOTEL", "ACTIVE", "MANUAL"));

        BusinessEntitlementApplicationService.BusinessEntitlementView created = service.enable("hotel");

        assertThat(created.businessType()).isEqualTo("HOTEL");
        assertThat(created.status()).isEqualTo("ACTIVE");
    }
}

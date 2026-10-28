package io.openware.platform.tenant.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.domain.entitlement.repository.BusinessEntitlementRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户业态开通：总部业务组合和门店工作台的可见性来源。
 * 门店的业务类型仍由 tnt_store 决定；本服务只回答“租户是否开通该业态”。
 */
@Service
public class BusinessEntitlementApplicationService {

    private static final String PERMISSION_VIEW = "tenant.overview.view";
    private static final String PERMISSION_MANAGE = "tenant.business.manage";

    private final BusinessEntitlementRepository repository;

    public BusinessEntitlementApplicationService(BusinessEntitlementRepository repository) {
        this.repository = repository;
    }

    public List<BusinessEntitlementView> list() {
        TenantContext context = requireContext(PERMISSION_VIEW);
        return repository.findActive(context.tenantId()).stream()
                .map(row -> new BusinessEntitlementView(row.id(), row.businessType(), row.status(), row.source()))
                .toList();
    }

    @Transactional
    public BusinessEntitlementView enable(String businessType) {
        TenantContext context = requireContext(PERMISSION_MANAGE);
        String type = businessType == null ? "" : businessType.trim().toUpperCase();
        if (type.isBlank()) {
            throw new ApiException(400, "BUSINESS_TYPE_REQUIRED", "业态编码不能为空");
        }
        repository.activate(context.tenantId(), type, context.accountId());
        BusinessEntitlementRepository.BusinessEntitlement saved = repository.find(context.tenantId(), type);
        if (saved == null) {
            throw new ApiException(500, "ENTITLEMENT_WRITE_FAILED", "业态开通后读取失败");
        }
        return new BusinessEntitlementView(saved.id(), saved.businessType(), saved.status(), saved.source());
    }

    private TenantContext requireContext(String permission) {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(403, "TENANT_SCOPE_REQUIRED", "必须在租户上下文中查看业态开通");
        }
        if (context.permissions() == null || !context.permissions().contains(permission)) {
            throw new ApiException(403, "PERMISSION_DENIED", "缺少权限: " + permission);
        }
        return context;
    }

    public record BusinessEntitlementView(Long id, String businessType, String status, String source) {
    }
}

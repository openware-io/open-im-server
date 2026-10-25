package io.openware.platform.tenant.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.domain.tenant.repository.TenantWorkspaceConfigRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 租户工作区配置：门店默认独立工作台，也可切换为租户后台内的门店上下文。 */
@Service
public class TenantWorkspaceConfigApplicationService {

    public static final String MODE_INDEPENDENT = "INDEPENDENT";
    public static final String MODE_TENANT_CONTEXT = "TENANT_CONTEXT";
    private static final List<String> MODES = List.of(MODE_INDEPENDENT, MODE_TENANT_CONTEXT);

    private final TenantWorkspaceConfigRepository repository;

    public TenantWorkspaceConfigApplicationService(TenantWorkspaceConfigRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public String get() {
        return get(requireContext().tenantId());
    }

    @Transactional(readOnly = true)
    public String get(long tenantId) {
        String mode = repository.findStoreWorkspaceMode(tenantId);
        return mode == null ? MODE_INDEPENDENT : mode;
    }

    @Transactional
    public String update(String mode) {
        PermissionGuard.require("tenant.tenant.manage");
        TenantContext context = requireContext();
        if (mode == null || !MODES.contains(mode)) {
            throw new ApiException(400, "WORKSPACE_MODE_INVALID",
                    "门店工作区模式只能是 INDEPENDENT 或 TENANT_CONTEXT");
        }
        repository.saveStoreWorkspaceMode(context.tenantId(), mode, context.accountId());
        return mode;
    }

    private TenantContext requireContext() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        return context;
    }
}

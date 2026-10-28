package io.openware.platform.tenant.api.controller;

import io.openware.platform.tenant.application.TenantWorkspaceConfigApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/tenant")
public class InternalTenantWorkspaceController {

    private final TenantWorkspaceConfigApplicationService service;

    public InternalTenantWorkspaceController(TenantWorkspaceConfigApplicationService service) {
        this.service = service;
    }

    @GetMapping("/{tenantId}/workspace-mode")
    public TenantWorkspaceModeController.WorkspaceModeView workspaceMode(@PathVariable Long tenantId) {
        return new TenantWorkspaceModeController.WorkspaceModeView(service.get(tenantId));
    }
}

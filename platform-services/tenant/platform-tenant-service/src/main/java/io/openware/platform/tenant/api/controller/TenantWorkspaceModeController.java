package io.openware.platform.tenant.api.controller;

import io.openware.platform.tenant.application.TenantWorkspaceConfigApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/tenant/workspace-mode")
public class TenantWorkspaceModeController {

    private final TenantWorkspaceConfigApplicationService service;

    public TenantWorkspaceModeController(TenantWorkspaceConfigApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public WorkspaceModeView get() {
        return new WorkspaceModeView(service.get());
    }

    @PutMapping
    public WorkspaceModeView update(@RequestBody WorkspaceModeRequest request) {
        return new WorkspaceModeView(service.update(request == null ? null : request.storeWorkspaceMode()));
    }

    public record WorkspaceModeView(String storeWorkspaceMode) {}
    public record WorkspaceModeRequest(String storeWorkspaceMode) {}
}

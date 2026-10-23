package io.openware.platform.admin.application;

import io.openware.platform.admin.api.backend.AdminBackend;
import io.openware.platform.admin.infra.security.AdminContextHolder;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * SaaS 后台入口：由 IAM 解析出的 workspace 决定，而不是旧 AdminRole 硬编码。
 *
 * <p>平台角色只看到平台运营后台；租户/门店角色只看到租户后台；同一账号确实同时拥有两类
 * 角色时才同时返回，并必须在入口页显式选择。
 */
@Service
public class AdminBackendApplicationService {

    private final AdminRoleProfileService workspaces;

    public AdminBackendApplicationService(AdminRoleProfileService workspaces) {
        this.workspaces = workspaces;
    }

    public List<AdminBackend> backends() {
        var admin = AdminContextHolder.get();
        if (admin == null) return List.of();
        var resolved = workspaces.resolve(admin.platformAccountId(), admin.role());
        boolean platform = resolved.workspaces().contains("PLATFORM");
        boolean tenant = resolved.workspaces().stream()
                .anyMatch(code -> "HEADQUARTERS".equals(code) || "TENANT".equals(code) || "STORE".equals(code));
        List<AdminBackend> backends = new java.util.ArrayList<>();
        if (platform) {
            backends.add(new AdminBackend("platform", "平台运营后台", "/platform", "building"));
        }
        if (tenant) {
            backends.add(new AdminBackend("tenant", "租户后台", "/tenant", "shop"));
        }
        return List.copyOf(backends);
    }
}

package io.openware.platform.admin.application;

import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.domain.role.repository.AdminRoleProfileRepository;
import io.openware.platform.admin.infra.TenantIamDomainClient;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 角色工作台解析：把账号的角色绑定投影成工作区、默认落点和数据范围。
 *
 * <p>角色权限的权威仍在 tenant-service；本服务只读 role code，不复制权限规则。
 * 角色档案缺失或远端查询失败时回退到旧的账号角色，保证登录不被工作台配置阻断。
 */
@Service
@Slf4j
public class AdminRoleProfileService {

    private final AdminRoleProfileRepository profiles;
    private final TenantIamDomainClient tenantIamClient;

    public AdminRoleProfileService(AdminRoleProfileRepository profiles,
                                   TenantIamDomainClient tenantIamClient) {
        this.profiles = profiles;
        this.tenantIamClient = tenantIamClient;
    }

    public AdminWorkspace resolve(Long platformAccountId, AdminRole legacyRole) {
        // SUPER_ADMIN is a fixed BFF/break-glass role, not an IAM platform role.
        // Never surface it as platform.operator, otherwise the super admin and a
        // configurable platform operator look identical in effective permissions.
        if (legacyRole == AdminRole.SUPER_ADMIN) {
            return new AdminWorkspace(List.of(), List.of("PLATFORM"), "PLATFORM",
                    "/admin/platform/tenants");
        }
        List<String> roleCodes = roleCodes(platformAccountId, legacyRole);
        List<AdminRoleProfileRepository.RoleProfile> rows = profiles.findByRoleCodes(roleCodes);
        if (rows.isEmpty()) {
            rows = profiles.findByRoleCodes(fallbackRoleCodes(legacyRole));
        }
        if (rows.isEmpty()) {
            return new AdminWorkspace(List.of(), List.of("PLATFORM"), "PLATFORM", "/select");
        }
        List<TenantIamDomainClient.RoleBinding> bindings = roleBindings(platformAccountId);
        Long tenantId = bindings.stream()
                .map(TenantIamDomainClient.RoleBinding::tenantId)
                .filter(id -> id != null && id > 0)
                .findFirst().orElse(null);
        String storeMode = tenantIamClient.workspaceMode(tenantId);
        List<String> workspaces = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AdminRoleProfileRepository.RoleProfile profile : rows) {
            String workspace = effectiveWorkspace(profile.workspaceCode(), storeMode);
            if (seen.add(workspace)) {
                workspaces.add(workspace);
            }
        }
        AdminRoleProfileRepository.RoleProfile primary = rows.get(0);
        String landing = landing(primary, bindings);
        return new AdminWorkspace(roleCodes, workspaces,
                effectiveWorkspace(primary.workspaceCode(), storeMode), landing);
    }

    /** 门店角色默认进独立 STORE 工作区；租户配置为门店上下文时归入 HEADQUARTERS。 */
    private static String effectiveWorkspace(String workspace, String storeMode) {
        if ("STORE".equals(workspace) && "TENANT_CONTEXT".equals(storeMode)) {
            return "HEADQUARTERS";
        }
        return workspace;
    }

    private List<String> roleCodes(Long platformAccountId, AdminRole legacyRole) {
        List<TenantIamDomainClient.RoleBinding> bindings = roleBindings(platformAccountId);
        List<String> codes = bindings.stream()
                .filter(binding -> binding.roleCode() != null && !binding.roleCode().isBlank())
                .filter(binding -> binding.status() == null || "ACTIVE".equalsIgnoreCase(binding.status()))
                .map(TenantIamDomainClient.RoleBinding::roleCode)
                .distinct()
                .toList();
        return codes.isEmpty() ? fallbackRoleCodes(legacyRole) : codes;
    }

    private List<TenantIamDomainClient.RoleBinding> roleBindings(Long platformAccountId) {
        if (platformAccountId == null || platformAccountId <= 0) return List.of();
        try {
            return tenantIamClient.roleBindings(platformAccountId);
        } catch (RuntimeException exception) {
            log.warn("role bindings unavailable for platform account {}, falling back to legacy role", platformAccountId,
                    exception);
            return List.of();
        }
    }

    private String landing(AdminRoleProfileRepository.RoleProfile primary,
                           List<TenantIamDomainClient.RoleBinding> bindings) {
        String route = primary.landingRoute();
        if (route == null || route.isBlank()) return "/select";
        if (!route.contains("{storeId}")) return route;
        Long storeId = bindings.stream()
                .map(TenantIamDomainClient.RoleBinding::storeId)
                .filter(id -> id != null && id > 0)
                .findFirst()
                .orElse(null);
        return storeId == null ? "/select" : route.replace("{storeId}", String.valueOf(storeId));
    }

    private List<String> fallbackRoleCodes(AdminRole legacyRole) {
        if (legacyRole == AdminRole.TENANT_ADMIN) return List.of("tenant.owner");
        return List.of("platform.operator");
    }

    /** 平台侧角色模板/工作区档案。 */
    public List<RoleProfileView> listProfiles() {
        return profiles.findAll().stream().map(AdminRoleProfileService::toView).toList();
    }

    /** 更新角色模板的工作区/落点；角色权限本身仍由 tenant-service 管理。 */
    public RoleProfileView updateProfile(Long id, String workspaceCode, String landingRoute,
                                         String defaultScope, String defaultBusinessType, Integer sortNo) {
        AdminRoleProfileRepository.RoleProfile profile = profiles.findById(id);
        if (profile == null) {
            throw new IllegalArgumentException("角色工作台档案不存在: " + id);
        }
        if (workspaceCode != null && !List.of("PLATFORM", "HEADQUARTERS", "STORE", "IM").contains(workspaceCode)) {
            throw new IllegalArgumentException("非法工作区: " + workspaceCode);
        }
        if (defaultScope != null && !List.of("PLATFORM", "TENANT", "STORE", "IM").contains(defaultScope)) {
            throw new IllegalArgumentException("非法数据范围: " + defaultScope);
        }
        AdminRoleProfileRepository.RoleProfile updated = new AdminRoleProfileRepository.RoleProfile(
                profile.id(), profile.roleCode(),
                workspaceCode == null ? profile.workspaceCode() : workspaceCode,
                landingRoute == null || landingRoute.isBlank() ? profile.landingRoute() : landingRoute,
                defaultScope == null ? profile.defaultScope() : defaultScope,
                defaultBusinessType, profile.navProfile(),
                sortNo == null ? profile.sortNo() : sortNo, profile.status());
        profiles.update(updated);
        return toView(updated);
    }

    private static RoleProfileView toView(AdminRoleProfileRepository.RoleProfile profile) {
        return new RoleProfileView(profile.id(), profile.roleCode(), profile.workspaceCode(), profile.landingRoute(),
                profile.defaultScope(), profile.defaultBusinessType(), profile.navProfile(),
                profile.sortNo(), profile.status());
    }

    public record RoleProfileView(Long id, String roleCode, String workspaceCode, String landingRoute,
                                  String defaultScope, String defaultBusinessType, String navProfile,
                                  Integer sortNo, String status) {
    }

    public record AdminWorkspace(List<String> roles, List<String> workspaces, String defaultWorkspace, String landing) {}
}

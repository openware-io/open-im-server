package io.openware.platform.admin.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.infra.TenantIamDomainClient;
import io.openware.platform.admin.infra.persistence.mapper.AdminRoleProfileMapper;
import io.openware.platform.admin.infra.persistence.po.AdminRoleProfilePo;
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

    private final AdminRoleProfileMapper roleProfileMapper;
    private final TenantIamDomainClient tenantIamClient;

    public AdminRoleProfileService(AdminRoleProfileMapper roleProfileMapper,
                                   TenantIamDomainClient tenantIamClient) {
        this.roleProfileMapper = roleProfileMapper;
        this.tenantIamClient = tenantIamClient;
    }

    public AdminWorkspace resolve(Long platformAccountId, AdminRole legacyRole) {
        List<String> roleCodes = roleCodes(platformAccountId, legacyRole);
        List<AdminRoleProfilePo> profiles = profiles(roleCodes);
        if (profiles.isEmpty()) {
            profiles = profiles(fallbackRoleCodes(legacyRole));
        }
        if (profiles.isEmpty()) {
            return new AdminWorkspace(List.of(), List.of("PLATFORM"), "PLATFORM", "/select");
        }
        List<String> workspaces = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AdminRoleProfilePo profile : profiles) {
            if (seen.add(profile.getWorkspaceCode())) {
                workspaces.add(profile.getWorkspaceCode());
            }
        }
        AdminRoleProfilePo primary = profiles.get(0);
        String landing = landing(primary, roleBindings(platformAccountId));
        return new AdminWorkspace(roleCodes, workspaces, primary.getWorkspaceCode(), landing);
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

    private List<AdminRoleProfilePo> profiles(List<String> roleCodes) {
        if (roleCodes.isEmpty()) return List.of();
        return roleProfileMapper.selectList(new LambdaQueryWrapper<AdminRoleProfilePo>()
                .in(AdminRoleProfilePo::getRoleCode, roleCodes)
                .eq(AdminRoleProfilePo::getStatus, "ACTIVE")
                .orderByAsc(AdminRoleProfilePo::getSortNo)
                .orderByAsc(AdminRoleProfilePo::getId));
    }

    private String landing(AdminRoleProfilePo primary, List<TenantIamDomainClient.RoleBinding> bindings) {
        String route = primary.getLandingRoute();
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

    public record AdminWorkspace(List<String> roles, List<String> workspaces, String defaultWorkspace, String landing) {}
}

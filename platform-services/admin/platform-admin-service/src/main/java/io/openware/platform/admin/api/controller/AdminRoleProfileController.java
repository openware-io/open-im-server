package io.openware.platform.admin.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.platform.admin.application.AdminRoleProfileService;
import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.infra.security.AdminContextHolder;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 平台侧角色工作台模板（工作区/落点/数据范围）。角色权限仍归 tenant-service。 */
@RestController
@RequestMapping("/admin/iam/role-profiles")
public class AdminRoleProfileController {

    private final AdminRoleProfileService roleProfileService;

    public AdminRoleProfileController(AdminRoleProfileService roleProfileService) {
        this.roleProfileService = roleProfileService;
    }

    @GetMapping
    public List<AdminRoleProfileService.RoleProfileView> list() {
        requirePlatformOperator();
        return roleProfileService.listProfiles();
    }

    @PutMapping("/{id}")
    public AdminRoleProfileService.RoleProfileView update(@PathVariable Long id, @RequestBody UpdateRoleProfileRequest request) {
        requirePlatformOperator();
        return roleProfileService.updateProfile(id, request.workspaceCode(),
                request.landingRoute(), request.defaultScope(), request.defaultBusinessType(), request.sortNo());
    }

    private void requirePlatformOperator() {
        var admin = AdminContextHolder.get();
        if (admin == null || (admin.role() != AdminRole.SUPER_ADMIN && admin.role() != AdminRole.PLATFORM_ADMIN)) {
            throw new ApiException(403, "PLATFORM_SCOPE_REQUIRED", "角色模板只能由平台运营维护");
        }
    }

    public record UpdateRoleProfileRequest(String workspaceCode, String landingRoute, String defaultScope,
                                           String defaultBusinessType, Integer sortNo) {}

}

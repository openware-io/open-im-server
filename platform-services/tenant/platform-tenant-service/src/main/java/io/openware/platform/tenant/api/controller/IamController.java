package io.openware.platform.tenant.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.platform.tenant.api.converter.IamConverter;
import io.openware.platform.tenant.api.dto.PermissionView;
import io.openware.platform.tenant.api.dto.RoleView;
import io.openware.platform.tenant.api.dto.UserRoleView;
import io.openware.platform.tenant.application.IamAuthorizationApplicationService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SaaS 后台 IAM（角色/权限/用户角色分配，ADM-03）。
 * 权限与角色-权限为平台级（无 tenant_id），角色/用户角色为租户级。
 *
 * <p><b>授权边界规则不在本类</b>：作用域白名单判定在 {@code Permission#grantableTo}，作用域与归属字段的
 * 形状约束在 {@code UserRoleBinding#scopeShapeValid}，读/写编排与审计在
 * {@link IamAuthorizationApplicationService}。控制器只做协议转换。
 */
@RestController
@RequestMapping("/admin/iam")
public class IamController {
    private final IamAuthorizationApplicationService authorizationService;

    public IamController(IamAuthorizationApplicationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    /** 权限目录（平台级，无租户上下文也可读）。 */
    @GetMapping("/permissions")
    public List<PermissionView> listPermissions() {
        return authorizationService.listPermissions().stream().map(IamConverter::toView).toList();
    }

    /** 脱敏权限管理：预置角色及其 member.pii.view 状态（租户管理员配置下级人员的脱敏权限）。 */
    @GetMapping("/masking")
    public List<MaskingRoleView> masking() {
        return authorizationService.listMaskingRoles().stream()
                .map(role -> new MaskingRoleView(role.roleId(), role.code(), role.name(), role.piiView()))
                .toList();
    }

    @PostMapping("/roles")
    public RoleView createRole(@RequestBody CreateRoleRequest req) {
        return IamConverter.toView(authorizationService.createRole(
                req.tenantId(), req.code(), req.name(), req.roleType()));
    }

    @GetMapping("/roles")
    public List<RoleView> listRoles(@RequestParam Long tenantId) {
        return authorizationService.listRoles(tenantId).stream().map(IamConverter::toView).toList();
    }

    @PostMapping("/roles/{roleId}/permissions")
    public void assignPermissions(@PathVariable Long roleId, @RequestBody AssignPermissionsRequest req) {
        authorizationService.assignPermissions(roleId, req.permissionIds());
    }

    /** 角色权限码列表（脱敏权限管理等 UI 展示用）。 */
    @GetMapping("/roles/{roleId}/permissions")
    public List<String> listRolePermissions(@PathVariable Long roleId) {
        return authorizationService.listRolePermissionCodes(roleId);
    }

    /** 单权限开关（脱敏权限 member.pii.view 等）：给角色增删单个权限，不影响其它权限。 */
    @PostMapping("/roles/{roleId}/permissions/toggle")
    public void togglePermission(@PathVariable Long roleId, @RequestBody TogglePermissionRequest req) {
        authorizationService.togglePermission(roleId, req.permissionCode(), req.granted());
    }

    /** 给账号分配角色（租户/组织/门店作用域）。 */
    @PostMapping("/user-roles")
    public UserRoleView assignUserRole(@RequestBody AssignUserRoleRequest req) {
        var binding = authorizationService.assignUserRole(req.accountId(), req.tenantId(),
                req.organizationId(), req.storeId(), req.roleId(), req.scopeType());
        return IamConverter.toView(binding);
    }

    /** 撤销账号在某作用域下的角色绑定（软删，status → REVOKED）。 */
    @DeleteMapping("/user-roles/{userRoleId}")
    public void revokeUserRole(@PathVariable Long userRoleId) {
        Long accountId = authorizationService.revokeUserRole(userRoleId);
        if (accountId == null) {
            throw new ApiException(404, "USER_ROLE_NOT_FOUND", "角色绑定不存在");
        }
    }

    /** 账号的 iam_user_role 绑定 id 列表（供运营人员列表精确撤销/删除用）。 */
    @GetMapping("/accounts/{accountId}/user-roles")
    public List<Long> listUserRoleIds(@PathVariable Long accountId) {
        return authorizationService.listUserRoleIds(accountId);
    }

    public record CreateRoleRequest(Long tenantId, String code, String name, String roleType) {}
    public record AssignPermissionsRequest(List<Long> permissionIds) {}
    public record TogglePermissionRequest(String permissionCode, boolean granted) {}
    public record MaskingRoleView(Long roleId, String code, String name, boolean piiView) {}
    public record AssignUserRoleRequest(Long accountId, Long tenantId, Long organizationId, Long storeId,
                                        Long roleId, String scopeType) {}
}

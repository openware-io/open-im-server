package io.openware.platform.tenant.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.tenant.api.converter.IamConverter;
import io.openware.platform.tenant.api.dto.PermissionView;
import io.openware.platform.tenant.api.dto.RoleView;
import io.openware.platform.tenant.api.dto.UserRoleView;
import io.openware.platform.tenant.application.IamAuthorizationApplicationService;
import io.openware.platform.tenant.domain.iam.model.Role;
import io.openware.platform.tenant.domain.iam.repository.PermissionRepository;
import io.openware.platform.tenant.domain.iam.repository.RoleRepository;
import io.openware.platform.tenant.domain.iam.repository.UserRoleRepository;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
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
 * 形状约束在 {@code UserRoleBinding#scopeShapeValid}，编排在 {@link IamAuthorizationApplicationService}。
 * 控制器只做协议转换与审计。
 */
@RestController
@RequestMapping("/admin/iam")
public class IamController {
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final IamAuthorizationApplicationService authorizationService;
    private final AuditClient auditClient;

    public IamController(RoleRepository roleRepository, PermissionRepository permissionRepository,
                         UserRoleRepository userRoleRepository,
                         IamAuthorizationApplicationService authorizationService,
                         AuditClient auditClient) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.userRoleRepository = userRoleRepository;
        this.authorizationService = authorizationService;
        this.auditClient = auditClient;
    }

    /** 权限目录（平台级，无租户上下文也可读）。 */
    @GetMapping("/permissions")
    public List<PermissionView> listPermissions() {
        return permissionRepository.listActive().stream().map(IamConverter::toView).toList();
    }

    /** 脱敏权限管理：预置角色及其 member.pii.view 状态（租户管理员配置下级人员的脱敏权限）。 */
    @GetMapping("/masking")
    public List<MaskingRoleView> masking() {
        return roleRepository.listMaskingRoles().stream()
                .map(role -> new MaskingRoleView(role.roleId(), role.code(), role.name(), role.piiView()))
                .toList();
    }

    @PostMapping("/roles")
    @Transactional
    public RoleView createRole(@RequestBody CreateRoleRequest req) {
        Role role = roleRepository.insert(Role.create(req.tenantId(), req.code(), req.name(), req.roleType()));
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(req.tenantId())
                .action("iam.role.create")
                .resourceType("role")
                .resourceId(String.valueOf(role.id()))
                .resourceName(role.name())
                .idempotencyKey("role:" + role.id())
                .detailJson("{\"tenantId\":" + req.tenantId() + ",\"roleType\":\"" + role.roleType() + "\"}")
                .build());
        return IamConverter.toView(role);
    }

    @GetMapping("/roles")
    public List<RoleView> listRoles(@RequestParam Long tenantId) {
        return roleRepository.listActiveByTenant(tenantId).stream().map(IamConverter::toView).toList();
    }

    @PostMapping("/roles/{roleId}/permissions")
    @Transactional
    public void assignPermissions(@PathVariable Long roleId, @RequestBody AssignPermissionsRequest req) {
        authorizationService.assignPermissions(roleId, req.permissionIds());
        auditClient.recordAsync(AuditClient.AuditRecord.of(
                null, null, "iam.role.permissions.assign", "role",
                String.valueOf(roleId), null, "role-perm:" + roleId,
                "{\"permissionIds\":\"" + req.permissionIds() + "\"}"));
    }

    /** 角色权限码列表（脱敏权限管理等 UI 展示用）。 */
    @GetMapping("/roles/{roleId}/permissions")
    public List<String> listRolePermissions(@PathVariable Long roleId) {
        return authorizationService.listRolePermissionCodes(roleId);
    }

    /** 单权限开关（脱敏权限 member.pii.view 等）：给角色增删单个权限，不影响其它权限。 */
    @PostMapping("/roles/{roleId}/permissions/toggle")
    @Transactional
    public void togglePermission(@PathVariable Long roleId, @RequestBody TogglePermissionRequest req) {
        authorizationService.togglePermission(roleId, req.permissionCode(), req.granted());
        auditClient.recordAsync(AuditClient.AuditRecord.of(
                null, null, "iam.role.permission.toggle", "role",
                String.valueOf(roleId), null, "role-perm-toggle:" + roleId + ":" + req.permissionCode(),
                "permissionCode=" + req.permissionCode() + ",granted=" + req.granted()));
    }

    /** 给账号分配角色（租户/组织/门店作用域）。 */
    @PostMapping("/user-roles")
    @Transactional
    public UserRoleView assignUserRole(@RequestBody AssignUserRoleRequest req) {
        var binding = authorizationService.assignUserRole(req.accountId(), req.tenantId(), req.organizationId(),
                req.storeId(), req.roleId(), req.scopeType());
        auditClient.recordAsync(AuditClient.AuditRecord.of(
                req.tenantId(), null, "iam.user_role.assign", "user_role",
                String.valueOf(binding.id()), null, "user-role:" + binding.id(),
                "{\"accountId\":" + req.accountId() + ",\"roleId\":" + req.roleId() + "}"));
        return IamConverter.toView(binding);
    }

    /** 撤销账号在某作用域下的角色绑定（软删，status → REVOKED）。 */
    @DeleteMapping("/user-roles/{userRoleId}")
    @Transactional
    public void revokeUserRole(@PathVariable Long userRoleId) {
        Long accountId = authorizationService.revokeUserRole(userRoleId);
        if (accountId == null) {
            throw new ApiException(404, "USER_ROLE_NOT_FOUND", "角色绑定不存在");
        }
        auditClient.recordAsync(AuditClient.AuditRecord.of(
                null, null, "iam.user_role.revoke", "user_role",
                String.valueOf(userRoleId), null, "user-role:" + userRoleId,
                "{\"accountId\":" + accountId + "}"));
    }

    /** 账号的 iam_user_role 绑定 id 列表（供运营人员列表精确撤销/删除用）。 */
    @GetMapping("/accounts/{accountId}/user-roles")
    public List<Long> listUserRoleIds(@PathVariable Long accountId) {
        return userRoleRepository.findIdsByAccount(accountId);
    }

    public record CreateRoleRequest(Long tenantId, String code, String name, String roleType) {}
    public record AssignPermissionsRequest(List<Long> permissionIds) {}
    public record TogglePermissionRequest(String permissionCode, boolean granted) {}
    public record MaskingRoleView(Long roleId, String code, String name, boolean piiView) {}
    public record AssignUserRoleRequest(Long accountId, Long tenantId, Long organizationId, Long storeId,
                                        Long roleId, String scopeType) {}
}

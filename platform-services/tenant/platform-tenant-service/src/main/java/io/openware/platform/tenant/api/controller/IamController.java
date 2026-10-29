package io.openware.platform.tenant.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.api.converter.IamConverter;
import io.openware.platform.tenant.api.converter.PermissionCatalogConverter;
import io.openware.platform.tenant.api.dto.PermissionMatrixVersionView;
import io.openware.platform.tenant.api.dto.PermissionMatrixView;
import io.openware.platform.tenant.api.dto.PermissionView;
import io.openware.platform.tenant.api.dto.RolePermissionCountView;
import io.openware.platform.tenant.api.dto.RolePermissionSnapshotView;
import io.openware.platform.tenant.api.dto.RoleView;
import io.openware.platform.tenant.api.dto.UserRoleView;
import io.openware.platform.tenant.application.IamAuthorizationApplicationService;
import io.openware.platform.tenant.application.PermissionCatalogApplicationService;
import io.openware.platform.tenant.application.RoleBindingApplicationService;
import io.openware.platform.tenant.domain.iam.model.Role;
import io.openware.platform.tenant.domain.iam.model.RoleBinding;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final RoleBindingApplicationService roleBindingService;
    private final PermissionCatalogApplicationService permissionCatalogService;

    public IamController(IamAuthorizationApplicationService authorizationService,
                         RoleBindingApplicationService roleBindingService,
                         PermissionCatalogApplicationService permissionCatalogService) {
        this.authorizationService = authorizationService;
        this.roleBindingService = roleBindingService;
        this.permissionCatalogService = permissionCatalogService;
    }

    /** 权限目录（平台级，无租户上下文也可读）。 */
    @GetMapping("/permissions")
    public List<PermissionView> listPermissions() {
        requireRoleRead();
        return authorizationService.listPermissions().stream().map(IamConverter::toView).toList();
    }

    /**
     * 权限目录矩阵：权限列表 + 全局角色授权覆盖，一次请求返回同一快照。
     *
     * <p>{@code refresh=true} 跳过缓存读取（用于页面手动刷新）；缓存键由数据库目录版本决定，
     * 角色/授权写入后版本变化，会自动重建，不存在跨实例旧数据。
     *
     * <p>与 {@code GET /admin/iam/permissions} 同口径：平台级只读字典（权限点 + 全局角色模板），
     * 不包含租户数据，因此不要求租户上下文；写入仍走角色/权限授予接口。
     */
    @GetMapping("/permissions/matrix")
    public ResponseEntity<PermissionMatrixView> permissionMatrix(
            @RequestParam(name = "refresh", required = false, defaultValue = "false") boolean refresh,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        requireRoleRead();
        String currentVersion = permissionCatalogService.currentVersion();
        String etag = "\"" + currentVersion + "\"";
        if (!refresh && etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        PermissionCatalogApplicationService.Result result = permissionCatalogService.snapshot(refresh);
        String responseEtag = "\"" + result.snapshot().version() + "\"";
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        // 刷新请求必须返回完整响应体：不设置 ETag，避免框架按 If-None-Match 自动转 304。
        if (!refresh) {
            builder.eTag(responseEtag);
        }
        return builder.body(PermissionCatalogConverter.toView(result.snapshot(), result.cached()));
    }

    /** 权限目录版本：前端聚焦/可见性恢复时轻量校验，版本未变不拉全量矩阵。 */
    @GetMapping("/permissions/matrix/version")
    public PermissionMatrixVersionView permissionMatrixVersion() {
        requireRoleRead();
        return new PermissionMatrixVersionView(permissionCatalogService.currentVersion());
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
        var context = TenantContextHolder.get();
        if (context == null) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少上下文");
        }
        requireRoleWrite();
        if (context.platformScope()) {
            if (!"PLATFORM".equals(req.scopeLevel())) {
                throw new ApiException(400, "ROLE_SCOPE_INVALID", "平台上下文只能创建 PLATFORM 角色");
            }
            return IamConverter.toView(authorizationService.createPlatformRole(req.code(), req.name()));
        }
        if (!"TENANT".equals(req.scopeLevel()) && !"STORE".equals(req.scopeLevel())) {
            throw new ApiException(400, "ROLE_SCOPE_INVALID", "租户只能创建 TENANT 或 STORE 角色");
        }
        return IamConverter.toView(authorizationService.createRole(
                context.tenantId(), req.code(), req.name(), "TENANT", req.scopeLevel()));
    }

    @GetMapping("/roles")
    public List<RoleView> listRoles(@RequestParam Long tenantId) {
        TenantContext context = TenantContextHolder.get();
        if (context == null) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少上下文");
        }
        if (!context.platformScope() && !tenantId.equals(context.tenantId())) {
            throw new ApiException(403, "TENANT_SCOPE_DENIED", "不能查看其他租户的角色");
        }
        return authorizationService.listRoles(tenantId).stream().map(IamConverter::toView).toList();
    }

    /** 当前上下文可见的角色目录：平台上下文返回平台角色，租户上下文返回可分配角色。 */
    @GetMapping("/roles/catalog")
    public List<RoleView> roleCatalog() {
        TenantContext context = TenantContextHolder.get();
        if (context == null) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少上下文");
        }
        requireRoleRead();
        return visibleRoles(context).stream().map(IamConverter::toView).toList();
    }

    /** 租户可分配角色：全局预置角色 + 本租户自定义角色，不含平台角色。 */
    @GetMapping("/roles/assignable")
    public List<RoleView> listAssignableRoles() {
        var context = requireTenantContext();
        requireRoleRead();
        return authorizationService.listAssignableRoles(context.tenantId()).stream()
                .map(IamConverter::toView).toList();
    }

    /** 角色权限计数（批量）：角色列表一次加载，避免逐角色 N+1。 */
    @GetMapping("/roles/permission-counts")
    public List<RolePermissionCountView> rolePermissionCounts() {
        TenantContext context = TenantContextHolder.get();
        if (context == null) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少上下文");
        }
        requireRoleRead();
        List<Role> roles = visibleRoles(context);
        Map<Long, Long> counts = authorizationService.permissionCounts(roles.stream().map(Role::id).toList());
        return roles.stream()
                .map(role -> new RolePermissionCountView(role.id(), counts.getOrDefault(role.id(), 0L)))
                .toList();
    }

    private List<Role> visibleRoles(TenantContext context) {
        return context.platformScope()
                ? authorizationService.listPlatformRoles()
                : authorizationService.listAssignableRoles(context.tenantId());
    }

    @PostMapping("/roles/{roleId}/permissions")
    public void assignPermissions(@PathVariable Long roleId, @RequestBody AssignPermissionsRequest req) {
        requireRoleWrite();
        authorizationService.assignPermissions(roleId, req.permissionIds(), req.expectedVersion());
    }

    /** 角色权限快照：权限编码 + 版本（编辑弹窗用版本做乐观并发校验）。 */
    @GetMapping("/roles/{roleId}/permissions/snapshot")
    public RolePermissionSnapshotView rolePermissionSnapshot(@PathVariable Long roleId) {
        requireRoleRead();
        IamAuthorizationApplicationService.RolePermissionSnapshot snapshot =
                authorizationService.rolePermissionSnapshot(roleId);
        return new RolePermissionSnapshotView(snapshot.permissionCodes(), snapshot.version());
    }

    /** 角色权限码列表（脱敏权限管理等 UI 展示用）。 */
    @GetMapping("/roles/{roleId}/permissions")
    public List<String> listRolePermissions(@PathVariable Long roleId) {
        requireRoleRead();
        return authorizationService.listRolePermissionCodes(roleId);
    }

    /** 单权限开关（脱敏权限 member.pii.view 等）：给角色增删单个权限，不影响其它权限。 */
    @PostMapping("/roles/{roleId}/permissions/toggle")
    public void togglePermission(@PathVariable Long roleId, @RequestBody TogglePermissionRequest req) {
        requireRoleWrite();
        authorizationService.togglePermission(roleId, req.permissionCode(), req.granted(), req.expectedVersion());
    }

    /** 给账号分配角色（写 iam_role_binding，主体为 STAFF membership）。 */
    @PostMapping("/user-roles")
    public UserRoleView assignUserRole(@RequestBody AssignUserRoleRequest req) {
        requireRoleWrite();
        RoleBinding binding = roleBindingService.assignByAccount(req.accountId(), req.tenantId(),
                req.organizationId(), req.storeId(), req.roleId(), req.scopeType());
        return IamConverter.toView(binding, req.accountId());
    }

    @DeleteMapping("/roles/{roleId}/accounts/{accountId}")
    public void revokeRoleFromAccount(@PathVariable Long roleId, @PathVariable Long accountId) {
        requireRoleWrite();
        roleBindingService.revokeByAccountAndRole(accountId, roleId);
    }

    @PutMapping("/roles/{roleId}/accounts/{accountId}/scope")
    public void updateRoleAssignmentScope(@PathVariable Long roleId, @PathVariable Long accountId,
                                          @RequestBody UpdateRoleScopeRequest request) {
        requireRoleWrite();
        roleBindingService.updateScopeByAccountAndRole(accountId, roleId,
                request.organizationId(), request.storeId(), request.scopeType());
    }

    /** 撤销成员角色绑定（软删 status → REVOKED）。 */
    @DeleteMapping("/user-roles/{userRoleId}")
    public void revokeUserRole(@PathVariable Long userRoleId) {
        requireRoleWrite();
        roleBindingService.revokeAny(userRoleId);
    }

    /** 账号的成员角色绑定 id 列表（供运营人员精确撤销/删除）。 */
    @GetMapping("/accounts/{accountId}/user-roles")
    public List<Long> listUserRoleIds(@PathVariable Long accountId) {
        requireRoleWrite();
        return roleBindingService.listIdsByAccount(accountId);
    }

    public record CreateRoleRequest(Long tenantId, String code, String name, String roleType, String scopeLevel) {}
    public record AssignPermissionsRequest(List<Long> permissionIds, String expectedVersion) {
        /** 兼容旧调用方：不带版本时保持无条件替换语义。 */
        public AssignPermissionsRequest(List<Long> permissionIds) {
            this(permissionIds, null);
        }
    }

    public record TogglePermissionRequest(String permissionCode, boolean granted, String expectedVersion) {
        /** 兼容旧调用方：不带版本时保持无条件切换语义。 */
        public TogglePermissionRequest(String permissionCode, boolean granted) {
            this(permissionCode, granted, null);
        }
    }
    public record MaskingRoleView(Long roleId, String code, String name, boolean piiView) {}
    public record AssignUserRoleRequest(Long accountId, Long tenantId, Long organizationId, Long storeId,
                                        Long roleId, String scopeType) {}

    public record UpdateRoleScopeRequest(Long organizationId, Long storeId, String scopeType) {}

    private TenantContext requireTenantContext() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        return context;
    }

    /**
     * IAM 读接口的角色查看门禁。
     *
     * <p>平台后台工作区按设计不传租户上下文，平台菜单/会话由 platform-admin-service 的
     * AdminRole（SUPER_ADMIN / PLATFORM_ADMIN）控制；这里在无上下文时放行平台级只读，
     * 有平台上下文时要求平台角色查看权限，有租户上下文时要求租户角色查看权限。
     */
    private static void requireRoleRead() {
        TenantContext context = TenantContextHolder.get();
        if (context == null) {
            return;
        }
        if (context.platformScope()) {
            PermissionGuard.require("platform.iam.role.view");
            return;
        }
        PermissionGuard.require("iam.role.view");
    }

    /** 角色写操作：平台上下文要求平台角色管理权限，租户上下文要求租户角色管理权限。 */
    private static void requireRoleWrite() {
        TenantContext context = TenantContextHolder.get();
        if (context == null) {
            return;
        }
        if (context.platformScope()) {
            PermissionGuard.require("platform.iam.role.manage");
            return;
        }
        PermissionGuard.require("iam.role.manage");
    }
}

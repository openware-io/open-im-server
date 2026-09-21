package com.gvchat.platform.tenant.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.platform.tenant.application.PermissionSnapshotProvider;
import com.gvchat.platform.tenant.infra.persistence.mapper.PermissionMapper;
import com.gvchat.platform.tenant.infra.persistence.mapper.RoleMapper;
import com.gvchat.platform.tenant.infra.persistence.mapper.RolePermissionMapper;
import com.gvchat.platform.tenant.infra.persistence.mapper.UserRoleMapper;
import com.gvchat.platform.tenant.infra.persistence.po.PermissionPo;
import com.gvchat.platform.tenant.infra.persistence.po.RolePo;
import com.gvchat.platform.tenant.infra.persistence.po.RolePermissionPo;
import com.gvchat.platform.tenant.infra.persistence.po.UserRolePo;
import com.gvchat.platform.tenant.infra.persistence.row.MaskingRoleRow;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * SaaS 后台 IAM（角色/权限/用户角色分配，ADM-03）。
 * 权限与角色-权限为平台级（无 tenant_id），角色/用户角色为租户级。
 */
@RestController
@RequestMapping("/admin/iam")
public class IamController {
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final PermissionSnapshotProvider permissionSnapshotProvider;
    private final AuditClient auditClient;

    public IamController(RoleMapper roleMapper, PermissionMapper permissionMapper,
                         RolePermissionMapper rolePermissionMapper, UserRoleMapper userRoleMapper,
                         PermissionSnapshotProvider permissionSnapshotProvider,
                         AuditClient auditClient) {
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.permissionSnapshotProvider = permissionSnapshotProvider;
        this.auditClient = auditClient;
    }

    /** 权限目录（平台级，无租户上下文也可读）。 */
    @GetMapping("/permissions")
    public List<PermissionPo> listPermissions() {
        return permissionMapper.selectList(new QueryWrapper<PermissionPo>().eq("status", "ACTIVE"));
    }

    /** 脱敏权限管理：预置角色及其 member.pii.view 状态（租户管理员配置下级人员的脱敏权限）。 */
    @GetMapping("/masking")
    public List<MaskingRoleView> masking() {
        List<MaskingRoleRow> rows = permissionSnapshotProvider.maskingRoles();
        List<MaskingRoleView> result = new ArrayList<>();
        for (MaskingRoleRow row : rows) {
            result.add(new MaskingRoleView(row.getRoleId(), row.getCode(), row.getName(),
                    row.getPiiView() != null && row.getPiiView()));
        }
        return result;
    }

    /** 创建角色（租户级）。 */
    @PostMapping("/roles")
    public RolePo createRole(@RequestBody CreateRoleRequest req) {
        RolePo po = new RolePo();
        po.setTenantId(req.tenantId());
        po.setCode(req.code());
        po.setName(req.name());
        po.setRoleType(req.roleType() == null ? "TENANT" : req.roleType());
        po.setStatus("ACTIVE");
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        roleMapper.insert(po);
        // 角色新建同属授权变更：与 assignPermissions / togglePermission 同口径留痕
        // （此前本控制器只有「改授权」有审计，「建角色」这一入口没有，可新建一个未经审计的授权载体）。
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(req.tenantId())
                .action("iam.role.create")
                .resourceType("role")
                .resourceId(String.valueOf(po.getId()))
                .resourceName(po.getName())
                .idempotencyKey("role:" + po.getId())
                .detailJson("{\"tenantId\":" + req.tenantId() + ",\"roleType\":\"" + po.getRoleType() + "\"}")
                .build());
        return po;
    }

    /** 租户角色列表。 */
    @GetMapping("/roles")
    public List<RolePo> listRoles(@RequestParam Long tenantId) {
        return roleMapper.selectList(new QueryWrapper<RolePo>().eq("tenant_id", tenantId).eq("status", "ACTIVE"));
    }

    /** 给角色分配权限（覆盖式）。 */
    @PostMapping("/roles/{roleId}/permissions")
    @Transactional
    public void assignPermissions(@PathVariable Long roleId, @RequestBody AssignPermissionsRequest req) {
        rolePermissionMapper.delete(new QueryWrapper<RolePermissionPo>().eq("role_id", roleId));
        for (Long permissionId : req.permissionIds()) {
            RolePermissionPo rp = new RolePermissionPo();
            rp.setRoleId(roleId);
            rp.setPermissionId(permissionId);
            rolePermissionMapper.insert(rp);
        }
        // 角色-权限变更影响权限快照：逐出缓存（授权变更事件占位）
        permissionSnapshotProvider.evictAll();
        // 高风险写操作（授权变更）审计：异步占位。
        auditClient.recordAsync(AuditClient.AuditRecord.of(
                null, null, "iam.role.permissions.assign", "role",
                String.valueOf(roleId), null, "role-perm:" + roleId,
                "{\"permissionIds\":\"" + req.permissionIds() + "\"}"));
    }

    /** 角色权限码列表（脱敏权限管理等 UI 展示用）。 */
    @GetMapping("/roles/{roleId}/permissions")
    public List<String> listRolePermissions(@PathVariable Long roleId) {
        List<RolePermissionPo> rps = rolePermissionMapper.selectList(
                new QueryWrapper<RolePermissionPo>().eq("role_id", roleId));
        if (rps.isEmpty()) return List.of();
        List<Long> ids = new ArrayList<>();
        for (RolePermissionPo rp : rps) {
            ids.add(rp.getPermissionId());
        }
        List<PermissionPo> perms = permissionMapper.selectList(new QueryWrapper<PermissionPo>().in("id", ids));
        List<String> codes = new ArrayList<>();
        for (PermissionPo p : perms) {
            codes.add(p.getCode());
        }
        return codes;
    }

    /** 单权限开关（脱敏权限 member.pii.view 等）：给角色增删单个权限，不影响其它权限。 */
    @PostMapping("/roles/{roleId}/permissions/toggle")
    @Transactional
    public void togglePermission(@PathVariable Long roleId, @RequestBody TogglePermissionRequest req) {
        String permissionCode = req.permissionCode();
        List<PermissionPo> perms = permissionMapper.selectList(new QueryWrapper<PermissionPo>().eq("code", permissionCode));
        PermissionPo perm = perms.isEmpty() ? null : perms.get(0);
        if (perm == null) {
            throw new com.gvchat.common.exception.ApiException(404, "PERMISSION_NOT_FOUND", "权限不存在");
        }
        List<RolePermissionPo> existingList = rolePermissionMapper.selectList(new QueryWrapper<RolePermissionPo>()
                .eq("role_id", roleId).eq("permission_id", perm.getId()));
        boolean exists = !existingList.isEmpty();
        if (req.granted() && !exists) {
            RolePermissionPo rp = new RolePermissionPo();
            rp.setRoleId(roleId);
            rp.setPermissionId(perm.getId());
            rolePermissionMapper.insert(rp);
        } else if (!req.granted() && exists) {
            rolePermissionMapper.delete(new QueryWrapper<RolePermissionPo>()
                    .eq("role_id", roleId).eq("permission_id", perm.getId()));
        }
        permissionSnapshotProvider.evictAll();
        auditClient.recordAsync(AuditClient.AuditRecord.of(
                null, null, "iam.role.permission.toggle", "role",
                String.valueOf(roleId), null, "role-perm-toggle:" + roleId + ":" + req.permissionCode(),
                "permissionCode=" + req.permissionCode() + ",granted=" + req.granted()));
    }

    /** 给账号分配角色（租户/组织/门店作用域）。 */
    @PostMapping("/user-roles")
    public UserRolePo assignUserRole(@RequestBody AssignUserRoleRequest req) {
        UserRolePo po = new UserRolePo();
        po.setAccountId(req.accountId());
        po.setTenantId(req.tenantId());
        po.setOrganizationId(req.organizationId());
        po.setStoreId(req.storeId());
        po.setRoleId(req.roleId());
        po.setScopeType(req.scopeType() == null ? "TENANT" : req.scopeType());
        po.setStatus("ACTIVE");
        po.setAuthorizationVersion(1);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        userRoleMapper.insert(po);
        // iam_user_role 写操作：逐出该账号权限快照缓存（授权变更事件占位）
        permissionSnapshotProvider.evict(req.accountId());
        // 高风险写操作（授权变更）审计：异步占位。
        auditClient.recordAsync(AuditClient.AuditRecord.of(
                req.tenantId(), null, "iam.user_role.assign", "user_role",
                String.valueOf(po.getId()), null, "user-role:" + po.getId(),
                "{\"accountId\":" + req.accountId() + ",\"roleId\":" + req.roleId() + "}"));
        return po;
    }

    /** 撤销账号在某作用域下的角色绑定（软删，status → REVOKED）。 */
    @DeleteMapping("/user-roles/{userRoleId}")
    public void revokeUserRole(@PathVariable Long userRoleId) {
        Long accountId = permissionSnapshotProvider.revokeUserRole(userRoleId);
        if (accountId == null) {
            throw new com.gvchat.common.exception.ApiException(404, "USER_ROLE_NOT_FOUND", "角色绑定不存在");
        }
        auditClient.recordAsync(AuditClient.AuditRecord.of(
                null, null, "iam.user_role.revoke", "user_role",
                String.valueOf(userRoleId), null, "user-role:" + userRoleId,
                "{\"accountId\":" + accountId + "}"));
    }

    /** 账号的 iam_user_role 绑定 id 列表（供运营人员列表精确撤销/删除用）。 */
    @GetMapping("/accounts/{accountId}/user-roles")
    public List<Long> listUserRoleIds(@PathVariable Long accountId) {
        return permissionSnapshotProvider.userRoleIds(accountId);
    }

    public record CreateRoleRequest(Long tenantId, String code, String name, String roleType) {}
    public record AssignPermissionsRequest(List<Long> permissionIds) {}
    public record TogglePermissionRequest(String permissionCode, boolean granted) {}
    public record MaskingRoleView(Long roleId, String code, String name, boolean piiView) {}
    public record AssignUserRoleRequest(Long accountId, Long tenantId, Long organizationId, Long storeId,
                                        Long roleId, String scopeType) {}
}

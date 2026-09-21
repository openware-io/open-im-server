package com.gvchat.platform.admin.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.platform.admin.application.StaffAccountApplicationService;
import com.gvchat.platform.admin.infra.TenantIamDomainClient;
import com.gvchat.platform.admin.infra.persistence.mapper.SaaAdminAccountMapper;
import com.gvchat.platform.admin.infra.persistence.po.SaaAdminAccountPo;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.time.TimeRangeParams;
import com.gvchat.infrastructure.time.TimeRangeParams.TimeRange;
import com.gvchat.platform.admin.infra.security.AdminContextHolder;
import com.gvchat.platform.admin.infra.security.AdminTenantContextTokenSigner;
import com.gvchat.common.exception.ApiException;
import java.util.Objects;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * SaaS 后台运营人员管理：把 IM 账号开通为运营人员（员工），绑定角色与作用域。
 * 数据流：identity 服务查/建 EMPLOYEE 账号 → 建 saa_admin_account → tenant 服务分配 iam_user_role。
 */
@RestController
@RequestMapping("/admin/staff")
public class StaffController {

    private final SaaAdminAccountMapper saaAdminAccountMapper;
    private final TenantIamDomainClient tenantIamClient;
    private final AdminTenantContextTokenSigner contextTokens;
    private final StaffAccountApplicationService staffAccounts;

    public StaffController(SaaAdminAccountMapper saaAdminAccountMapper,
                           TenantIamDomainClient tenantIamClient, AdminTenantContextTokenSigner contextTokens,
                           StaffAccountApplicationService staffAccounts) {
        this.saaAdminAccountMapper = saaAdminAccountMapper;
        this.tenantIamClient = tenantIamClient;
        this.contextTokens = contextTokens;
        this.staffAccounts = staffAccounts;
    }

    /** 新增运营人员：把 IM 账号开通为员工并绑定角色。 */
    @PostMapping
    public AccountView create(@RequestBody CreateStaffRequest req) {
        TenantContext context = requireContext();
        if (!Objects.equals(req.tenantId(), context.tenantId())
                || (context.organizationId() != null && (!Objects.equals(req.organizationId(), context.organizationId()) || "TENANT".equals(req.scopeType())))
                || (context.storeId() != null && (!Objects.equals(req.storeId(), context.storeId()) || !"STORE".equals(req.scopeType())))) {
            throw new ApiException(403, "STAFF_SCOPE_FORBIDDEN", "不能跨运营范围授权");
        }
        if (req.roleId() == null || !("TENANT".equals(req.scopeType()) || "STORE".equals(req.scopeType()))
                || ("STORE".equals(req.scopeType()) && req.storeId() == null)
                || ("TENANT".equals(req.scopeType()) && (req.organizationId() != null || req.storeId() != null))) {
            throw new ApiException(400, "STAFF_SCOPE_INVALID", "请选择有效角色及租户或门店作用域");
        }
        if (!tenantIamClient.isStaffRole(req.roleId(), req.scopeType())) {
            throw new ApiException(403, "STAFF_ROLE_FORBIDDEN", "此角色不能用于当前运营范围");
        }
        if ("STORE".equals(req.scopeType()) && tenantIamClient.staffStores(context).stream().noneMatch(store ->
                Objects.equals(req.storeId(), store.id()) && Objects.equals(req.organizationId(), store.organizationId()))) {
            throw new ApiException(403, "STAFF_STORE_FORBIDDEN", "门店不在当前授权范围内");
        }
        return accountView(staffAccounts.create(new StaffAccountApplicationService.CreateCommand(req.username(), req.password(),
                req.imAccount(), req.displayName(), req.roleId(), req.scopeType(), req.tenantId(), req.organizationId(), req.storeId())));
    }

    @GetMapping("/options")
    public StaffOptions options() {
        TenantContext context = requireContext();
        return new StaffOptions(tenantIamClient.staffRoles().stream()
                .filter(role -> "STORE".equals(role.scopeType()) || (context.organizationId() == null && context.storeId() == null)).toList(),
                tenantIamClient.staffStores(context));
    }

    /**
     * 运营人员列表（含已绑定角色/作用域）。
     *
     * <p><b>时间区间</b>：{@code from}/{@code to} 按**账号创建时间** {@code created_at} 的闭区间筛选，
     * 统一口径见 {@link TimeRangeParams}：日期形态的 from/to 分别收口到当天起点与当天末尾
     * （{@code 23:59:59.999}），也接受 {@code yyyy-MM-ddTHH:mm:ss}；为空 = 不筛；
     * {@code from > to} → 400 {@code TIME_RANGE_INVALID}。
     *
     * <p>时间条件下推到 SQL（{@code created_at >= from AND created_at <= to}，不用函数包裹），
     * 后续的作用域过滤仍在 Java 侧完成，排序与既有行为不变。
     */
    @GetMapping
    public List<StaffView> list(@RequestParam(required = false) String from,
                                @RequestParam(required = false) String to) {
        TenantContext context = requireContext();
        TimeRange range = TimeRangeParams.parse(from, to);
        List<SaaAdminAccountPo> accounts = saaAdminAccountMapper.selectList(new LambdaQueryWrapper<SaaAdminAccountPo>()
                .eq(SaaAdminAccountPo::getRole, "TENANT_ADMIN")
                .ge(range.hasFrom(), SaaAdminAccountPo::getCreatedAt, range.fromInclusive())
                .le(range.hasTo(), SaaAdminAccountPo::getCreatedAt, range.toInclusive())
                .orderByAsc(SaaAdminAccountPo::getId));
        List<StaffView> result = new ArrayList<>();
        for (SaaAdminAccountPo po : accounts) {
            List<TenantIamDomainClient.RoleBinding> roles = List.of();
            if (po.getPlatformAccountId() != null) {
                roles = tenantIamClient.roleBindings(po.getPlatformAccountId());
            }
            boolean canManageAccount = !roles.isEmpty() && roles.stream().allMatch(binding -> withinScope(context, binding));
            roles = roles.stream().filter(binding -> withinScope(context, binding)).toList();
            if (roles.isEmpty()) continue;
            result.add(new StaffView(po.getId(), po.getUsername(), po.getDisplayName(),
                    po.getPlatformAccountId(), po.getImAccount() != null && !po.getImAccount().isBlank(),
                    po.getRole(), po.getStatus(), po.getCreatedAt(), roles, canManageAccount));
        }
        return result;
    }

    private TenantContext requireContext() {
        var admin = AdminContextHolder.get();
        if (admin == null || admin.tenantContextToken() == null) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "请先选择运营上下文");
        }
        TenantContext context;
        try {
            context = contextTokens.verify(admin.tenantContextToken());
        } catch (Exception exception) {
            throw new ApiException(401, "SAAS_CONTEXT_INVALID", "运营上下文已过期，请刷新页面");
        }
        if (!Objects.equals(admin.platformAccountId(), context.accountId())) {
            throw new ApiException(403, "STAFF_SCOPE_FORBIDDEN", "运营账号不匹配");
        }
        var snapshot = tenantIamClient.permissions(context.accountId(), context.tenantId(), context.organizationId(), context.storeId());
        if (!snapshot.permissions().contains("iam.role.manage")) {
            throw new ApiException(403, "STAFF_PERMISSION_REQUIRED", "无运营人员管理权限");
        }
        return context;
    }

    /** 启用/禁用运营人员（禁用后无法登录后台）。
     *  DISABLED 同时撤销其全部 iam_user_role（IM OAuth 进 B 端/后台的裁决按角色判定，
     *  禁用必须立即收回访问权）；ACTIVE 仅恢复后台账号状态（角色需重新开通时走删除+新增）。 */
    @PatchMapping("/{id}/status")
    public AccountView updateStatus(@PathVariable Long id, @RequestBody UpdateStatusRequest req) {
        SaaAdminAccountPo po = requireStaff(id);
        String status = req.status();
        if (status == null || (!"ACTIVE".equals(status) && !"DISABLED".equals(status))) {
            throw new com.gvchat.common.exception.ApiException(400, "STAFF_STATUS_INVALID", "状态仅支持 ACTIVE/DISABLED");
        }
        if ("DISABLED".equals(status) && po.getPlatformAccountId() != null) {
            for (Long userRoleId : tenantIamClient.listUserRoleIds(po.getPlatformAccountId())) {
                tenantIamClient.revokeUserRole(userRoleId);
            }
        }
        po.setStatus(status);
        po.setUpdatedAt(LocalDateTime.now());
        saaAdminAccountMapper.updateById(po);
        return accountView(po);
    }

    @PatchMapping("/{id}/im-binding")
    public AccountView bindImAccount(@PathVariable Long id, @RequestBody BindImAccountRequest req) {
        return accountView(staffAccounts.bindImAccount(requireStaff(id), req.imAccount()));
    }

    /** 解除运营人员的 IM 关联（幂等：未绑定也返回成功）。只清 im_account，账号本体与角色绑定都保留。 */
    @DeleteMapping("/{id}/im-binding")
    public AccountView unbindImAccount(@PathVariable Long id) {
        return accountView(staffAccounts.unbindImAccount(requireStaff(id)));
    }

    /** 删除运营人员（删除登录凭证 + 撤销全部角色绑定）。 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        SaaAdminAccountPo po = requireStaff(id);
        if (po.getPlatformAccountId() != null) {
            for (Long userRoleId : tenantIamClient.listUserRoleIds(po.getPlatformAccountId())) {
                tenantIamClient.revokeUserRole(userRoleId);
            }
        }
        saaAdminAccountMapper.deleteById(id);
    }

    private SaaAdminAccountPo requireStaff(Long id) {
        TenantContext context = requireContext();
        SaaAdminAccountPo po = saaAdminAccountMapper.selectById(id);
        if (po == null || !"TENANT_ADMIN".equals(po.getRole()) || po.getPlatformAccountId() == null) {
            throw new com.gvchat.common.exception.ApiException(404, "STAFF_NOT_FOUND", "运营人员不存在");
        }
        var bindings = tenantIamClient.roleBindings(po.getPlatformAccountId());
        if (bindings.isEmpty() || bindings.stream().anyMatch(binding -> !withinScope(context, binding))) {
            throw new ApiException(403, "STAFF_SCOPE_FORBIDDEN", "账号涉及当前运营范围以外的授权，不能执行全局账号操作");
        }
        return po;
    }

    private boolean withinScope(TenantContext context, TenantIamDomainClient.RoleBinding binding) {
        return Objects.equals(context.tenantId(), binding.tenantId())
                && (context.organizationId() == null || Objects.equals(context.organizationId(), binding.organizationId()))
                && (context.storeId() == null || Objects.equals(context.storeId(), binding.storeId()))
                && !"PLATFORM".equals(binding.scopeType());
    }

    public record CreateStaffRequest(String username, String password, String imAccount, String displayName, Long roleId, String scopeType,
                                     Long tenantId, Long organizationId, Long storeId) {}
    public record StaffOptions(List<TenantIamDomainClient.StaffRole> roles, List<TenantIamDomainClient.StaffStore> stores) {}
    public record AccountView(Long id, String username, String displayName, Long platformAccountId, boolean imBound, String status) {}
    private AccountView accountView(SaaAdminAccountPo account) {
        return new AccountView(account.getId(), account.getUsername(), account.getDisplayName(), account.getPlatformAccountId(),
                account.getImAccount() != null && !account.getImAccount().isBlank(), account.getStatus());
    }
    public record UpdateStatusRequest(String status) {}
    public record BindImAccountRequest(String imAccount) {}
    public record StaffView(Long id, String username, String displayName, Long platformAccountId, boolean imBound,
                            String role, String status, LocalDateTime createdAt,
                            List<TenantIamDomainClient.RoleBinding> roles, boolean canManageAccount) {}
}

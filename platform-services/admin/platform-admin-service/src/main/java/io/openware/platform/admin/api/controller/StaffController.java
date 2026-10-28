package io.openware.platform.admin.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.admin.application.StaffAccountApplicationService;
import io.openware.platform.admin.domain.model.ConsAdminAccount;
import io.openware.platform.admin.infra.TenantIamDomainClient;
import io.openware.platform.admin.infra.security.AdminContext;
import io.openware.platform.admin.infra.security.AdminContextHolder;
import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.infra.security.AdminTenantContextTokenSigner;
import java.time.LocalDateTime;
import java.util.List;
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

/**
 * SaaS 后台运营人员管理：把 IM 账号开通为运营人员（员工），绑定角色与作用域。
 * 数据流：identity 服务查/建 EMPLOYEE 账号 → 建 cons_admin_account → tenant 服务分配 iam_user_role。
 *
 * <p>本类只做入站适配：解析参数、校验签名上下文、调用应用服务、把用例结果转成响应。
 * 账号读写与作用域判定在应用服务与领域层，控制器不再直接持有持久化对象或 mapper
 * （standards/10：Controller 不得作为业务规则中心）。
 */
@RestController
@RequestMapping("/admin/staff")
public class StaffController {

    private final TenantIamDomainClient tenantIamClient;
    private final AdminTenantContextTokenSigner contextTokens;
    private final StaffAccountApplicationService staffAccounts;

    public StaffController(TenantIamDomainClient tenantIamClient, AdminTenantContextTokenSigner contextTokens,
                           StaffAccountApplicationService staffAccounts) {
        this.tenantIamClient = tenantIamClient;
        this.contextTokens = contextTokens;
        this.staffAccounts = staffAccounts;
    }

    /**
     * 平台运营为新租户创建首个租户管理员。
     *
     * <p>新租户创建接口只落 tnt_tenant，若没有这个引导端点，租户后台没有任何
     * 可登录账号。这里复用运营人员开通用例，并强制绑定全局预置 tenant.owner 角色。
     */
    @PostMapping("/bootstrap-owner")
    public AccountView bootstrapOwner(@RequestBody BootstrapOwnerRequest req) {
        AdminContext admin = AdminContextHolder.get();
        if (admin == null || (admin.role() != AdminRole.SUPER_ADMIN && admin.role() != AdminRole.PLATFORM_ADMIN)) {
            throw new ApiException(403, "PLATFORM_ADMIN_REQUIRED", "仅平台运营可创建租户初始管理员");
        }
        if (req == null || req.tenantId() == null || req.tenantId() <= 0) {
            throw new ApiException(400, "TENANT_ID_REQUIRED", "租户 ID 不能为空");
        }
        TenantIamDomainClient.StaffRole ownerRole = tenantIamClient.staffRoles().stream()
                .filter(role -> "tenant.owner".equals(role.code()))
                .findFirst()
                .orElseThrow(() -> new ApiException(500, "TENANT_OWNER_ROLE_MISSING", "缺少 tenant.owner 预置角色"));
        return accountView(staffAccounts.create(new StaffAccountApplicationService.CreateCommand(
                req.username(), req.password(), null, req.displayName(), ownerRole.roleId(),
                "TENANT", req.tenantId(), null, null), true));
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
     * <p>区间解析留在控制器（入站参数口径），下推到 SQL 的过滤与作用域筛选在应用服务完成。
     */
    @GetMapping
    public List<StaffView> list(@RequestParam(required = false) String from,
                                @RequestParam(required = false) String to) {
        TenantContext context = requireContext();
        TimeRange range = TimeRangeParams.parse(from, to);
        return staffAccounts.listStaff(context, range.hasFrom() ? range.fromInclusive() : null,
                        range.hasTo() ? range.toInclusive() : null).stream()
                .map(row -> new StaffView(row.id(), row.username(), row.displayName(), row.platformAccountId(),
                        row.imBound(), row.role(), row.status(), row.createdAt(), row.roles(),
                        row.canManageAccount()))
                .toList();
    }

    /**
     * 启用/禁用运营人员（禁用后无法登录后台）。
     * DISABLED 同时撤销其全部 iam_user_role（IM OAuth 进 B 端/后台的裁决按角色判定，
     * 禁用必须立即收回访问权）；ACTIVE 仅恢复后台账号状态（角色需重新开通时走删除+新增）。
     */
    @PatchMapping("/{id}/status")
    public AccountView updateStatus(@PathVariable Long id, @RequestBody UpdateStatusRequest req) {
        TenantContext context = requireContext();
        return accountView(staffAccounts.updateStatus(context, id, req.status()));
    }

    @PatchMapping("/{id}/im-binding")
    public AccountView bindImAccount(@PathVariable Long id, @RequestBody BindImAccountRequest req) {
        TenantContext context = requireContext();
        return accountView(staffAccounts.bindImAccount(
                staffAccounts.requireManageableStaff(context, id), req.imAccount()));
    }

    /** 解除运营人员的 IM 关联（幂等：未绑定也返回成功）。只清 im_account，账号本体与角色绑定都保留。 */
    @DeleteMapping("/{id}/im-binding")
    public AccountView unbindImAccount(@PathVariable Long id) {
        TenantContext context = requireContext();
        return accountView(staffAccounts.unbindImAccount(staffAccounts.requireManageableStaff(context, id)));
    }

    /** 删除运营人员（删除登录凭证 + 撤销全部角色绑定）。 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        staffAccounts.deleteStaff(requireContext(), id);
    }

    /**
     * 解析并校验签名上下文。
     *
     * <p>鉴权上下文提取属入站适配职责（standards/10 对 {@code api} 层的界定），因此留在控制器；
     * 依赖这些上下文的业务判定在应用服务。
     */
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

    public record BootstrapOwnerRequest(Long tenantId, String username, String password, String displayName) {}
    public record CreateStaffRequest(String username, String password, String imAccount, String displayName, Long roleId, String scopeType,
                                     Long tenantId, Long organizationId, Long storeId) {}
    public record StaffOptions(List<TenantIamDomainClient.StaffRole> roles, List<TenantIamDomainClient.StaffStore> stores) {}
    public record AccountView(Long id, String username, String displayName, Long platformAccountId, boolean imBound, String status) {}
    public record UpdateStatusRequest(String status) {}
    public record BindImAccountRequest(String imAccount) {}
    public record StaffView(Long id, String username, String displayName, Long platformAccountId, boolean imBound,
                            String role, String status, LocalDateTime createdAt,
                            List<TenantIamDomainClient.RoleBinding> roles, boolean canManageAccount) {}

    /** 领域模型到对外视图：转换只在适配层，领域模型不出入站边界。 */
    private AccountView accountView(ConsAdminAccount account) {
        return new AccountView(account.id(), account.username(), account.displayName(),
                account.platformAccountId(), account.imAccount() != null && !account.imAccount().isBlank(),
                account.status());
    }
}

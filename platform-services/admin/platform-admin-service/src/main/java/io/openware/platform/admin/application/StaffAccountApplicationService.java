package io.openware.platform.admin.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.domain.model.ConsAdminAccount;
import io.openware.platform.admin.domain.repository.ConsAdminAccountRepository;
import io.openware.platform.admin.domain.staff.readmodel.StaffAccountRow;
import io.openware.platform.admin.infra.IdentityDomainClient;
import io.openware.platform.admin.infra.ImUserResolveClient;
import io.openware.platform.admin.infra.TenantIamDomainClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StaffAccountApplicationService {
    private final ConsAdminAccountRepository accounts;
    private final IdentityDomainClient identity;
    private final ImUserResolveClient imUsers;
    private final TenantIamDomainClient iam;
    private final PasswordEncoder passwords;

    public StaffAccountApplicationService(ConsAdminAccountRepository accounts, IdentityDomainClient identity,
                                         ImUserResolveClient imUsers, TenantIamDomainClient iam, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.identity = identity;
        this.imUsers = imUsers;
        this.iam = iam;
        this.passwords = passwords;
    }

    @Transactional
    public ConsAdminAccount create(CreateCommand command) {
        return create(command, false);
    }

    /** 平台引导租户管理员时走内部 IAM 显式 tenantId；普通运营人员创建传 false。 */
    @Transactional
    public ConsAdminAccount create(CreateCommand command, String tenantContextToken) {
        return create(command, tenantContextToken != null);
    }

    @Transactional
    public ConsAdminAccount create(CreateCommand command, boolean platformBootstrap) {
        String username = command.username() == null ? "" : command.username().trim();
        if (!username.matches("[A-Za-z0-9][A-Za-z0-9_.-]{2,63}")) {
            throw new ApiException(400, "STAFF_USERNAME_INVALID", "登录账号应为 3–64 位字母、数字、下划线、点或短横线");
        }
        if (command.password() == null || command.password().isBlank() || command.password().length() < 8
                || command.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(400, "STAFF_PASSWORD_INVALID", "密码至少 8 位，且不超过 72 字节");
        }
        if (command.displayName() != null && command.displayName().length() > 64) {
            throw new ApiException(400, "STAFF_DISPLAY_NAME_INVALID", "显示名不能超过 64 位");
        }
        if (accounts.existsByUsername(username)) {
            throw new ApiException(409, "STAFF_USERNAME_EXISTS", "登录账号已存在");
        }
        String openId = null;
        if (command.imAccount() != null && !command.imAccount().isBlank()) {
            openId = imUsers.resolveOpenId(command.imAccount().trim());
            if (openId == null || openId.isBlank()) throw new ApiException(404, "IM_ACCOUNT_NOT_FOUND", "IM 账号不存在");
        }
        long accountId = openId == null
                ? identity.ensureAccount("SAAS_ADMIN", username, "USER")
                : identity.ensureAccount("IM", openId, "USER");
        if (accountId <= 0) throw new IllegalStateException("Identity returned invalid account ID");
        if (accounts.existsByPlatformAccountId(accountId)) {
            throw new ApiException(409, "STAFF_IM_ALREADY_BOUND", "该 IM 账号已绑定后台账号");
        }
        iam.ensureStaffMembership(accountId, command.tenantId(), command.organizationId());
        // 状态、默认角色与展示名回退规则由领域工厂给定；审计字段由适配器填充。
        ConsAdminAccount account = ConsAdminAccount.provision(username, passwords.encode(command.password()),
                command.displayName() == null || command.displayName().isBlank() ? username : command.displayName().trim(),
                command.imAccount() == null || command.imAccount().isBlank() ? null : command.imAccount().trim(),
                accountId);
        try {
            account = accounts.save(account);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(409, "STAFF_ACCOUNT_EXISTS", "登录账号或 IM 绑定已存在");
        }
        identity.bindLoginIdentity(account.platformAccountId(), "USERNAME", username, command.password());
        if (platformBootstrap) {
            iam.assignPlatformBootstrapRole(accountId, command.tenantId(), command.roleId(), command.scopeType(),
                    command.organizationId(), command.storeId());
        } else {
            iam.assignUserRole(accountId, command.tenantId(), command.organizationId(), command.storeId(), command.roleId(), command.scopeType());
        }
        return account;
    }

    @Transactional
    public ConsAdminAccount bindImAccount(ConsAdminAccount staff, String imAccount) {
        String normalizedImAccount = imAccount == null ? "" : imAccount.trim();
        if (normalizedImAccount.isEmpty()) {
            throw new ApiException(400, "IM_ACCOUNT_REQUIRED", "请填写 IM 账号");
        }
        // 换绑：旧 IM 账号仍然有效才拒绝（要求先显式解绑）；旧 IM 账号已不存在
        // （IM 后台删除了这个用户 → 统一账号模型里只解绑、员工账号本体保留）时允许直接覆盖，
        // 继续走下面的角色迁移逻辑 —— 否则员工账号会永久挂着一个已经不存在的 IM 用户名，再也绑不上新账号。
        if (staff.hasConflictingImBinding(normalizedImAccount) && !previousImIdentityGone(staff.imAccount())) {
            throw new ApiException(409, "STAFF_IM_REBIND_UNBIND_FIRST", "该运营人员已关联其他 IM 账号，请先解绑后再关联");
        }
        String openId = imUsers.resolveOpenId(normalizedImAccount);
        if (openId == null || openId.isBlank()) {
            throw new ApiException(404, "IM_ACCOUNT_NOT_FOUND", "IM 账号不存在");
        }
        long targetAccountId = identity.ensureAccount("IM", openId, "USER");
        if (targetAccountId <= 0) {
            throw new IllegalStateException("Identity returned invalid account ID");
        }
        if (Objects.equals(staff.platformAccountId(), targetAccountId)) {
            return accounts.save(staff.withImAccount(normalizedImAccount));
        }
        if (accounts.existsByPlatformAccountIdExcluding(targetAccountId, staff.id())) {
            throw new ApiException(409, "STAFF_IM_ALREADY_BOUND", "该 IM 账号已绑定后台账号");
        }
        List<TenantIamDomainClient.RoleBinding> sourceBindings = iam.roleBindings(staff.platformAccountId()).stream()
                .filter(binding -> "ACTIVE".equals(binding.status()))
                .toList();
        if (sourceBindings.isEmpty()) {
            throw new ApiException(409, "STAFF_ROLE_REQUIRED", "运营人员没有有效角色，不能关联 IM 账号");
        }
        Set<Long> originalTargetRoleIds = new HashSet<>(iam.listUserRoleIds(targetAccountId));
        try {
            for (TenantIamDomainClient.RoleBinding binding : sourceBindings) {
                iam.assignUserRole(targetAccountId, binding.tenantId(), binding.organizationId(), binding.storeId(),
                        binding.roleId(), binding.scopeType());
            }
            for (Long roleId : iam.listUserRoleIds(staff.platformAccountId())) {
                iam.revokeUserRole(roleId);
            }
            // 换绑同时改变 IM 绑定与平台账号口径，两步都是领域迁移，返回新实例后整体落库。
            ConsAdminAccount rebound = staff.withPlatformAccountId(targetAccountId)
                    .withImAccount(normalizedImAccount);
            return accounts.save(rebound);
        } catch (RuntimeException exception) {
            restoreMissingBindings(staff.platformAccountId(), sourceBindings);
            revokeNewTargetBindings(targetAccountId, originalTargetRoleIds);
            throw exception;
        }
    }

    /**
     * 旧 IM 标识是否已经不存在（IM 后台删除用户后，统一账号模型里只解绑、员工账号本体保留）。
     *
     * <p>判据两步：① 按用户名解析 IM open_id（{@code im_<id>}）——解析不到就说明 IM 用户已不存在
     * （IM 用户行被管理员硬删除）；
     * ② 解析到了再查统一账号模型里的 IM 身份落点（identity 内部只读端点），{@code found=false}
     * 说明该 IM 身份已被清理，旧绑定同样失效。
     *
     * <p><b>fail-closed</b>：任何一步查询失败都返回 false（仍按「旧绑定有效」拒绝换绑），
     * 宁可让运营先显式解绑，也不误覆盖一个可能仍然有效的 IM 关联。
     */
    private boolean previousImIdentityGone(String previousImAccount) {
        try {
            String previousOpenId = imUsers.resolveOpenId(previousImAccount);
            if (previousOpenId == null || previousOpenId.isBlank()) {
                return true;
            }
            return !identity.imIdentityExists(previousOpenId);
        } catch (RuntimeException failure) {
            log.warn("判断旧 IM 标识是否仍存在失败，保守拒绝换绑: staffImAccount={}, cause={}",
                    previousImAccount, failure.getMessage());
            return false;
        }
    }

    /**
     * 解除运营人员当前的 IM 关联（显式解绑，幂等）。
     *
     * <p>只清 {@code cons_admin_account.im_account}：账号本体、登录凭证与角色绑定全部保留 ——
     * 解绑的是「这个人还挂不挂某个 IM 身份」，不是收回他的运营权限。未绑定（im_account 为空）时直接返回，
     * 不发 UPDATE（重复点击不会产生多余写操作）。
     *
     * <p>{@code im_account} 上有唯一索引，但 MySQL 唯一索引允许多行为 NULL，解绑不影响其它账号。
     */
    @Transactional
    public ConsAdminAccount unbindImAccount(ConsAdminAccount staff) {
        if (staff.imAccount() == null || staff.imAccount().isBlank()) {
            return staff;
        }
        // 解绑是"把 im_account 置为 NULL"，不是"不动这个字段"：端口用独立的 clearImAccount 表达，
        // 避免 MyBatis-Plus 的 NOT_NULL 更新策略把 null 从 UPDATE 里剔除导致解绑静默失效。
        accounts.clearImAccount(staff.id());
        return staff.withoutImAccount();
    }

    private void restoreMissingBindings(Long accountId, List<TenantIamDomainClient.RoleBinding> originalBindings) {
        try {
            Set<RoleBindingKey> activeBindings = iam.roleBindings(accountId).stream()
                    .filter(binding -> "ACTIVE".equals(binding.status()))
                    .map(RoleBindingKey::from)
                    .collect(Collectors.toSet());
            for (TenantIamDomainClient.RoleBinding binding : originalBindings) {
                if (!activeBindings.contains(RoleBindingKey.from(binding))) {
                    iam.assignUserRole(accountId, binding.tenantId(), binding.organizationId(), binding.storeId(),
                            binding.roleId(), binding.scopeType());
                }
            }
        } catch (RuntimeException ignored) {
            // 补偿失败必须留痕：调用方据此判断授权是否已完全还原，静默吞掉会让不一致不可见。
            log.warn("restoreMissingBindings failed for accountId={}, authorization may be incomplete",
                    accountId, ignored);
        }
    }

    private void revokeNewTargetBindings(long targetAccountId, Set<Long> originalTargetRoleIds) {
        try {
            for (Long roleId : iam.listUserRoleIds(targetAccountId)) {
                if (!originalTargetRoleIds.contains(roleId)) {
                    iam.revokeUserRole(roleId);
                }
            }
        } catch (RuntimeException ignored) {
            // 同上：回滚失败不能静默，否则越权绑定可能残留而无人知晓。
            log.warn("revokeNewTargetBindings failed for targetAccountId={}, extra bindings may remain",
                    targetAccountId, ignored);
        }
    }

    private record RoleBindingKey(Long roleId, Long tenantId, Long organizationId, Long storeId, String scopeType) {
        private static RoleBindingKey from(TenantIamDomainClient.RoleBinding binding) {
            return new RoleBindingKey(binding.roleId(), binding.tenantId(), binding.organizationId(), binding.storeId(), binding.scopeType());
        }
    }

    /**
     * 按运营上下文列出运营人员（含可管理性判定）。
     *
     * <p>列表查询走**只读投影**（{@link StaffAccountRow}）：createdAt 是记账列，仅供展示，不进领域模型。
     * 时间区间下推到端口，不在应用层拼查询条件；作用域过滤留在应用层，因为它依赖签名上下文。
     *
     * <p>只保留在当前作用域内的角色绑定：绑定全在范围内的账号可被整体管理（{@code canManageAccount}），
     * 过滤后没有任何可见绑定的账号不出现在列表里。
     */
    public List<StaffListItem> listStaff(TenantContext context, LocalDateTime fromInclusive,
                                         LocalDateTime toInclusive) {
        List<StaffListItem> result = new ArrayList<>();
        for (StaffAccountRow row : accounts.listStaffRows(AdminRole.TENANT_ADMIN, fromInclusive, toInclusive)) {
            ConsAdminAccount account = row.account();
            List<TenantIamDomainClient.RoleBinding> roles = account.platformAccountId() == null
                    ? List.of()
                    : iam.roleBindings(account.platformAccountId());
            boolean canManageAccount = !roles.isEmpty()
                    && roles.stream().allMatch(binding -> withinScope(context, binding));
            List<TenantIamDomainClient.RoleBinding> visible =
                    roles.stream().filter(binding -> withinScope(context, binding)).toList();
            if (visible.isEmpty()) {
                continue;
            }
            result.add(new StaffListItem(account.id(), account.username(), account.displayName(),
                    account.platformAccountId(), row.boundToIm(),
                    account.role() == null ? null : account.role().name(), account.status(),
                    row.createdAt(), visible, canManageAccount));
        }
        return result;
    }

    /**
     * 取一个可被当前上下文整体管理的运营人员；否则拒绝。
     *
     * <p>这类"全局账号操作"的前置校验放在应用服务而不是控制器：它需要同时看账号、角色绑定与签名上下文，
     * 属于用例编排，不是入站适配的职责。
     */
    public ConsAdminAccount requireManageableStaff(TenantContext context, Long id) {
        ConsAdminAccount account = accounts.findById(id).orElse(null);
        if (account == null || account.role() != AdminRole.TENANT_ADMIN
                || account.platformAccountId() == null) {
            throw new ApiException(404, "STAFF_NOT_FOUND", "运营人员不存在");
        }
        var bindings = iam.roleBindings(account.platformAccountId());
        if (bindings.isEmpty() || bindings.stream().anyMatch(binding -> !withinScope(context, binding))) {
            throw new ApiException(403, "STAFF_SCOPE_FORBIDDEN", "账号涉及当前运营范围以外的授权，不能执行全局账号操作");
        }
        return account;
    }

    /**
     * 启用/禁用运营人员。禁用同时撤销其全部角色绑定（角色是 B 端访问的裁决依据，禁用必须立即收回访问权）；
     * 启用只恢复账号状态，角色需通过重新开通获得。
     */
    @Transactional
    public ConsAdminAccount updateStatus(TenantContext context, Long id, String status) {
        if (status == null || (!"ACTIVE".equals(status) && !"DISABLED".equals(status))) {
            throw new ApiException(400, "STAFF_STATUS_INVALID", "状态仅支持 ACTIVE/DISABLED");
        }
        ConsAdminAccount account = requireManageableStaff(context, id);
        if ("DISABLED".equals(status)) {
            for (Long userRoleId : iam.listUserRoleIds(account.platformAccountId())) {
                iam.revokeUserRole(userRoleId);
            }
        }
        return accounts.save(account.withStatus(status));
    }

    /** 删除运营人员：撤销全部角色绑定后删除账号。 */
    @Transactional
    public void deleteStaff(TenantContext context, Long id) {
        ConsAdminAccount account = requireManageableStaff(context, id);
        for (Long userRoleId : iam.listUserRoleIds(account.platformAccountId())) {
            iam.revokeUserRole(userRoleId);
        }
        accounts.deleteById(id);
    }

    /** 角色绑定是否落在当前签名上下文范围内；PLATFORM 作用域一律视为范围外。 */
    private boolean withinScope(TenantContext context, TenantIamDomainClient.RoleBinding binding) {
        return Objects.equals(context.tenantId(), binding.tenantId())
                && (context.organizationId() == null || Objects.equals(context.organizationId(), binding.organizationId()))
                && (context.storeId() == null || Objects.equals(context.storeId(), binding.storeId()))
                && !"PLATFORM".equals(binding.scopeType());
    }

    /**
     * 运营人员列表项（用例输出）。
     *
     * <p>放应用层而不是 API 层：它是用例的查询结果，控制器再据此转成响应 DTO
     * （standards/10：Command/Query/Result 属 application，且不得成为 Controller 入参）。
     */
    public record StaffListItem(Long id, String username, String displayName, Long platformAccountId,
                                boolean imBound, String role, String status, LocalDateTime createdAt,
                                List<TenantIamDomainClient.RoleBinding> roles, boolean canManageAccount) {}

    public record CreateCommand(String username, String password, String imAccount, String displayName,
                                Long roleId, String scopeType, Long tenantId, Long organizationId, Long storeId) {}
}

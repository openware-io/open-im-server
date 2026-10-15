package io.openware.platform.admin.application;

import io.openware.common.exception.ApiException;
import io.openware.platform.admin.domain.model.ConsAdminAccount;
import io.openware.platform.admin.domain.repository.ConsAdminAccountRepository;
import io.openware.platform.admin.infra.IdentityDomainClient;
import io.openware.platform.admin.infra.ImUserResolveClient;
import io.openware.platform.admin.infra.TenantIamDomainClient;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
                ? identity.ensureAccount("SAAS_ADMIN", UUID.randomUUID().toString(), "EMPLOYEE")
                : identity.ensureAccount("IM", openId, "EMPLOYEE");
        if (accountId <= 0) throw new IllegalStateException("Identity returned invalid account ID");
        if (accounts.existsByPlatformAccountId(accountId)) {
            throw new ApiException(409, "STAFF_IM_ALREADY_BOUND", "该 IM 账号已绑定后台账号");
        }
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
        iam.assignUserRole(accountId, command.tenantId(), command.organizationId(), command.storeId(), command.roleId(), command.scopeType());
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
        long targetAccountId = identity.ensureAccount("IM", openId, "EMPLOYEE");
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

    public record CreateCommand(String username, String password, String imAccount, String displayName,
                                Long roleId, String scopeType, Long tenantId, Long organizationId, Long storeId) {}
}

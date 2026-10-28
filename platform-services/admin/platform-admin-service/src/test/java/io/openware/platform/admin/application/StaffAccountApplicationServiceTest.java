package io.openware.platform.admin.application;

import io.openware.common.exception.ApiException;
import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.domain.model.ConsAdminAccount;
import io.openware.platform.admin.domain.repository.ConsAdminAccountRepository;
import io.openware.platform.admin.infra.IdentityDomainClient;
import io.openware.platform.admin.infra.ImUserResolveClient;
import io.openware.platform.admin.infra.TenantIamDomainClient;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.List;

/**
 * 运营人员开通/换绑/解绑。
 *
 * <p>替身是**领域端口**：测试不再依赖 MyBatis mapper、持久化对象或 Wrapper 的内部形状。
 * 此前解绑用例断言的是 {@code LambdaUpdateWrapper} 的 SET 子句，等于把持久化实现细节写进了测试；
 * 现在解绑表达为端口的 {@code clearImAccount}，测试断言的是"确实要求清空"这一意图。
 */
class StaffAccountApplicationServiceTest {
    private final ConsAdminAccountRepository accounts = mock(ConsAdminAccountRepository.class);
    private final IdentityDomainClient identity = mock(IdentityDomainClient.class);
    private final ImUserResolveClient imUsers = mock(ImUserResolveClient.class);
    private final TenantIamDomainClient iam = mock(TenantIamDomainClient.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final StaffAccountApplicationService service = new StaffAccountApplicationService(accounts, identity, imUsers, iam, passwords);

    /** 端口的 save 回填主键后返回（真实适配器在 insert 后回填，测试替身等价模拟）。 */
    private void savingReturnsArgument() {
        when(accounts.save(any(ConsAdminAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void unboundStaffReceivesIdentityPasswordAndIamRole() {
        savingReturnsArgument();
        when(identity.ensureAccount(eq("SAAS_ADMIN"), anyString(), eq("USER"))).thenReturn(51L);
        var result = service.create(command(null, "TestPass123"));
        assertEquals(51L, result.platformAccountId());
        assertTrue(passwords.matches("TestPass123", result.passwordHash()));
        assertEquals("staff1", result.displayName());
        // 开通用例的不变量：状态可用、角色取默认值，由领域工厂而不是调用方逐字段赋值。
        assertEquals(ConsAdminAccount.STATUS_ACTIVE, result.status());
        assertEquals(AdminRole.TENANT_ADMIN, result.role());
        verify(iam).assignUserRole(51L, 100L, 101L, 102L, 3L, "STORE");
        verifyNoInteractions(imUsers);
    }

    @Test
    void optionalImUsesSameIdentityAsOAuth() {
        savingReturnsArgument();
        when(imUsers.resolveOpenId("im-user")).thenReturn("im_150");
        when(identity.ensureAccount("IM", "im_150", "USER")).thenReturn(61L);
        var result = service.create(command("im-user", "TestPass123"));
        assertEquals(61L, result.platformAccountId());
        verify(iam).assignUserRole(61L, 100L, 101L, 102L, 3L, "STORE");
    }

    @Test
    void missingImAndInvalidPasswordsDoNotProvision() {
        assertThrows(ApiException.class, () -> service.create(command(null, "short")));
        assertThrows(ApiException.class, () -> service.create(command(null, "密".repeat(25))));
        assertThrows(ApiException.class, () -> service.create(command("missing", "TestPass123")));
        verifyNoInteractions(identity, iam);
        verify(accounts, never()).save(any(ConsAdminAccount.class));
    }

    @Test
    void existingUsernameCannotBeOverwritten() {
        when(accounts.existsByUsername(any())).thenReturn(true);
        assertThrows(ApiException.class, () -> service.create(command(null, "TestPass123")));
        verifyNoInteractions(identity, iam, imUsers);
    }

    @Test
    void bindImMovesActiveRolesToExistingImIdentity() {
        savingReturnsArgument();
        var staff = staffAccount(20L, 51L, null);
        when(imUsers.resolveOpenId("im-user")).thenReturn("im_150");
        when(identity.ensureAccount("IM", "im_150", "USER")).thenReturn(61L);
        when(iam.roleBindings(51L)).thenReturn(List.of(activeBinding()));
        when(iam.listUserRoleIds(61L)).thenReturn(List.of());
        when(iam.listUserRoleIds(51L)).thenReturn(List.of(701L));

        var result = service.bindImAccount(staff, "im-user");

        assertEquals(61L, result.platformAccountId());
        assertEquals("im-user", result.imAccount());
        verify(iam).assignUserRole(61L, 100L, 101L, 102L, 3L, "STORE");
        verify(iam).revokeUserRole(701L);
        verify(accounts).save(any(ConsAdminAccount.class));
    }

    @Test
    void bindImAssignmentFailureDoesNotUpdateStaffAccount() {
        var staff = staffAccount(20L, 51L, null);
        when(imUsers.resolveOpenId("im-user")).thenReturn("im_150");
        when(identity.ensureAccount("IM", "im_150", "USER")).thenReturn(61L);
        when(iam.roleBindings(51L)).thenReturn(List.of(activeBinding()));
        when(iam.listUserRoleIds(61L)).thenReturn(List.of(), List.of(801L));
        doThrow(new IllegalStateException("IAM unavailable")).when(iam)
                .assignUserRole(61L, 100L, 101L, 102L, 3L, "STORE");

        assertThrows(IllegalStateException.class, () -> service.bindImAccount(staff, "im-user"));

        // 领域迁移返回新实例，失败路径不落库，原实例保持不变。
        assertEquals(51L, staff.platformAccountId());
        verify(accounts, never()).save(any(ConsAdminAccount.class));
        verify(iam).revokeUserRole(801L);
    }

    @Test
    void bindImRejectsASecondStaffAccount() {
        var staff = staffAccount(20L, 51L, null);
        when(imUsers.resolveOpenId("im-user")).thenReturn("im_150");
        when(identity.ensureAccount("IM", "im_150", "USER")).thenReturn(61L);
        when(accounts.existsByPlatformAccountIdExcluding(61L, 20L)).thenReturn(true);

        assertThrows(ApiException.class, () -> service.bindImAccount(staff, "im-user"));

        verifyNoInteractions(iam);
        verify(accounts, never()).save(any(ConsAdminAccount.class));
    }

    private StaffAccountApplicationService.CreateCommand command(String im, String password) {
        return new StaffAccountApplicationService.CreateCommand("staff1", password, im, "", 3L, "STORE", 100L, 101L, 102L);
    }

    // —— 换绑（旧 IM 账号已被删除）与显式解绑 ——

    /** 员工账号：id + 平台账号 + 当前 im_account。 */
    private ConsAdminAccount staffAccount(long id, long platformAccountId, String imAccount) {
        return new ConsAdminAccount(id, "staff1", "hash", "Staff", imAccount, null, platformAccountId,
                AdminRole.TENANT_ADMIN, ConsAdminAccount.STATUS_ACTIVE);
    }

    private TenantIamDomainClient.RoleBinding activeBinding() {
        return new TenantIamDomainClient.RoleBinding(
                3L, 100L, 101L, 102L, "store.manager", "Manager", "STORE", "Tenant", "Store", "ACTIVE");
    }

    /** 旧 IM 用户已不存在（IM 后台删除用户）：允许换绑，并照样迁移角色。 */
    @Test
    void rebindOverwritesImAccountWhenPreviousImUserIsGone() {
        savingReturnsArgument();
        var staff = staffAccount(20L, 51L, "old-user");
        when(imUsers.resolveOpenId("old-user")).thenReturn(null);
        when(imUsers.resolveOpenId("new-user")).thenReturn("im_150");
        when(identity.ensureAccount("IM", "im_150", "USER")).thenReturn(61L);
        when(iam.roleBindings(51L)).thenReturn(List.of(activeBinding()));
        when(iam.listUserRoleIds(61L)).thenReturn(List.of());
        when(iam.listUserRoleIds(51L)).thenReturn(List.of(701L));

        var result = service.bindImAccount(staff, "new-user");

        assertEquals(61L, result.platformAccountId());
        assertEquals("new-user", result.imAccount());
        verify(iam).assignUserRole(61L, 100L, 101L, 102L, 3L, "STORE");
        verify(iam).revokeUserRole(701L);
        verify(accounts).save(any(ConsAdminAccount.class));
    }

    /** 旧 IM 身份在统一账号模型里已被清理（IM 用户行还在）：同样算「旧绑定失效」，允许换绑。 */
    @Test
    void rebindOverwritesImAccountWhenPreviousIdentityWasPurged() {
        savingReturnsArgument();
        var staff = staffAccount(20L, 51L, "old-user");
        when(imUsers.resolveOpenId("old-user")).thenReturn("im_140");
        when(identity.imIdentityExists("im_140")).thenReturn(false);
        when(imUsers.resolveOpenId("new-user")).thenReturn("im_150");
        when(identity.ensureAccount("IM", "im_150", "USER")).thenReturn(61L);
        when(iam.roleBindings(51L)).thenReturn(List.of(activeBinding()));
        when(iam.listUserRoleIds(61L)).thenReturn(List.of());
        when(iam.listUserRoleIds(51L)).thenReturn(List.of(701L));

        var result = service.bindImAccount(staff, "new-user");

        assertEquals(61L, result.platformAccountId());
        assertEquals("new-user", result.imAccount());
        verify(accounts).save(any(ConsAdminAccount.class));
    }

    /** 旧 IM 账号仍然有效 → 409 STAFF_IM_REBIND_UNBIND_FIRST，绝不静默覆盖有效绑定。 */
    @Test
    void rebindRejectsWhenPreviousImIdentityStillExists() {
        var staff = staffAccount(20L, 51L, "old-user");
        when(imUsers.resolveOpenId("old-user")).thenReturn("im_140");
        when(identity.imIdentityExists("im_140")).thenReturn(true);

        ApiException failure = assertThrows(ApiException.class, () -> service.bindImAccount(staff, "new-user"));

        assertEquals(409, failure.getStatus());
        assertEquals("STAFF_IM_REBIND_UNBIND_FIRST", failure.getCode());
        assertEquals("old-user", staff.imAccount());
        verify(identity, never()).ensureAccount(anyString(), anyString(), anyString());
        verify(accounts, never()).save(any(ConsAdminAccount.class));
    }

    /** fail-closed：旧 IM 存在性查询失败（IM 侧或 identity 侧）时按「旧绑定仍有效」拒绝换绑。 */
    @Test
    void rebindIsRejectedWhenOldImLookupFails() {
        var identityFailure = staffAccount(20L, 51L, "old-user");
        when(imUsers.resolveOpenId("old-user")).thenReturn("im_140");
        when(identity.imIdentityExists("im_140")).thenThrow(new IllegalStateException("identity unavailable"));

        assertEquals("STAFF_IM_REBIND_UNBIND_FIRST",
                assertThrows(ApiException.class, () -> service.bindImAccount(identityFailure, "new-user")).getCode());

        var imFailure = staffAccount(21L, 52L, "old-user-2");
        when(imUsers.resolveOpenId("old-user-2")).thenThrow(new IllegalStateException("im unavailable"));

        assertEquals("STAFF_IM_REBIND_UNBIND_FIRST",
                assertThrows(ApiException.class, () -> service.bindImAccount(imFailure, "new-user")).getCode());
        verify(accounts, never()).save(any(ConsAdminAccount.class));
    }

    /** 换绑时新 IM 账号已被另一个后台账号占用 → 仍必须是 409 STAFF_IM_ALREADY_BOUND。 */
    @Test
    void rebindRejectsWhenTargetImAccountIsTakenByAnotherStaff() {
        var staff = staffAccount(20L, 51L, "old-user");
        when(imUsers.resolveOpenId("old-user")).thenReturn(null);
        when(imUsers.resolveOpenId("new-user")).thenReturn("im_150");
        when(identity.ensureAccount("IM", "im_150", "USER")).thenReturn(61L);
        when(accounts.existsByPlatformAccountIdExcluding(61L, 20L)).thenReturn(true);

        ApiException failure = assertThrows(ApiException.class, () -> service.bindImAccount(staff, "new-user"));

        assertEquals(409, failure.getStatus());
        assertEquals("STAFF_IM_ALREADY_BOUND", failure.getCode());
        verifyNoInteractions(iam);
        verify(accounts, never()).save(any(ConsAdminAccount.class));
    }

    /**
     * 解绑：必须显式要求清空 im_account。
     *
     * <p>断言的是端口意图而非 Wrapper 的 SET 子句：默认 NOT_NULL 更新策略会把 null 字段从 UPDATE 中剔除，
     * 因此"清空"必须由独立端口方法承载，否则解绑会静默失效。
     */
    @Test
    void unbindClearsImAccountExplicitly() {
        var staff = staffAccount(20L, 51L, "im-user");

        var result = service.unbindImAccount(staff);

        assertNull(result.imAccount());
        verify(accounts).clearImAccount(20L);
        verify(accounts, never()).save(any(ConsAdminAccount.class));
        verifyNoInteractions(iam);
    }

    /** 解绑幂等：本来就没绑定 → 直接返回，不发任何写操作。 */
    @Test
    void unbindIsIdempotentWhenNothingBound() {
        var staff = staffAccount(20L, 51L, null);

        var result = service.unbindImAccount(staff);

        assertNull(result.imAccount());
        verify(accounts, never()).clearImAccount(any());
        verify(accounts, never()).save(any(ConsAdminAccount.class));
    }
}

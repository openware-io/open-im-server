package com.gvchat.platform.admin.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.platform.admin.infra.IdentityDomainClient;
import com.gvchat.platform.admin.infra.ImUserResolveClient;
import com.gvchat.platform.admin.infra.TenantIamDomainClient;
import com.gvchat.platform.admin.infra.persistence.mapper.SaaAdminAccountMapper;
import com.gvchat.platform.admin.infra.persistence.po.SaaAdminAccountPo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.List;
import java.util.Objects;

class StaffAccountApplicationServiceTest {
    private final SaaAdminAccountMapper accounts = mock(SaaAdminAccountMapper.class);
    private final IdentityDomainClient identity = mock(IdentityDomainClient.class);
    private final ImUserResolveClient imUsers = mock(ImUserResolveClient.class);
    private final TenantIamDomainClient iam = mock(TenantIamDomainClient.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final StaffAccountApplicationService service = new StaffAccountApplicationService(accounts, identity, imUsers, iam, passwords);

    @BeforeEach
    void registerTableInfo() {
        // 纯单元测试没有 MyBatis 上下文：解绑用 LambdaUpdateWrapper 显式把 im_account 置 NULL，
        // 断言 SET 子句前必须先注册 TableInfo（否则列名无法解析）。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SaaAdminAccountPo.class);
    }

    @Test
    void unboundStaffReceivesIdentityPasswordAndIamRole() {
        when(identity.ensureAccount(eq("SAAS_ADMIN"), anyString(), eq("EMPLOYEE"))).thenReturn(51L);
        var result = service.create(command(null, "TestPass123"));
        assertEquals(51L, result.getPlatformAccountId());
        assertTrue(passwords.matches("TestPass123", result.getPasswordHash()));
        assertEquals("staff1", result.getDisplayName());
        verify(iam).assignUserRole(51L, 100L, 101L, 102L, 3L, "STORE");
        verifyNoInteractions(imUsers);
    }

    @Test
    void optionalImUsesSameIdentityAsOAuth() {
        when(imUsers.resolveOpenId("im-user")).thenReturn("im_150");
        when(identity.ensureAccount("IM", "im_150", "EMPLOYEE")).thenReturn(61L);
        var result = service.create(command("im-user", "TestPass123"));
        assertEquals(61L, result.getPlatformAccountId());
        verify(iam).assignUserRole(61L, 100L, 101L, 102L, 3L, "STORE");
    }

    @Test
    void missingImAndInvalidPasswordsDoNotProvision() {
        assertThrows(ApiException.class, () -> service.create(command(null, "short")));
        assertThrows(ApiException.class, () -> service.create(command(null, "密".repeat(25))));
        assertThrows(ApiException.class, () -> service.create(command("missing", "TestPass123")));
        verifyNoInteractions(identity, iam);
        verify(accounts, never()).insert(any(com.gvchat.platform.admin.infra.persistence.po.SaaAdminAccountPo.class));
    }

    @Test
    void existingUsernameCannotBeOverwritten() {
        when(accounts.selectCount(any())).thenReturn(1L);
        assertThrows(ApiException.class, () -> service.create(command(null, "TestPass123")));
        verifyNoInteractions(identity, iam, imUsers);
    }

    @Test
    void bindImMovesActiveRolesToExistingImIdentity() {
        var staff = new com.gvchat.platform.admin.infra.persistence.po.SaaAdminAccountPo();
        staff.setId(20L);
        staff.setPlatformAccountId(51L);
        when(imUsers.resolveOpenId("im-user")).thenReturn("im_150");
        when(identity.ensureAccount("IM", "im_150", "EMPLOYEE")).thenReturn(61L);
        when(iam.roleBindings(51L)).thenReturn(List.of(new TenantIamDomainClient.RoleBinding(
                3L, 100L, 101L, 102L, "store.manager", "Manager", "STORE", "Tenant", "Store", "ACTIVE")));
        when(iam.listUserRoleIds(61L)).thenReturn(List.of());
        when(iam.listUserRoleIds(51L)).thenReturn(List.of(701L));

        var result = service.bindImAccount(staff, "im-user");

        assertEquals(61L, result.getPlatformAccountId());
        assertEquals("im-user", result.getImAccount());
        verify(iam).assignUserRole(61L, 100L, 101L, 102L, 3L, "STORE");
        verify(iam).revokeUserRole(701L);
        verify(accounts).updateById(staff);
    }

    @Test
    void bindImAssignmentFailureDoesNotUpdateStaffAccount() {
        var staff = new com.gvchat.platform.admin.infra.persistence.po.SaaAdminAccountPo();
        staff.setId(20L);
        staff.setPlatformAccountId(51L);
        var binding = new TenantIamDomainClient.RoleBinding(
                3L, 100L, 101L, 102L, "store.manager", "Manager", "STORE", "Tenant", "Store", "ACTIVE");
        when(imUsers.resolveOpenId("im-user")).thenReturn("im_150");
        when(identity.ensureAccount("IM", "im_150", "EMPLOYEE")).thenReturn(61L);
        when(iam.roleBindings(51L)).thenReturn(List.of(binding));
        when(iam.listUserRoleIds(61L)).thenReturn(List.of(), List.of(801L));
        doThrow(new IllegalStateException("IAM unavailable")).when(iam)
                .assignUserRole(61L, 100L, 101L, 102L, 3L, "STORE");

        assertThrows(IllegalStateException.class, () -> service.bindImAccount(staff, "im-user"));

        assertEquals(51L, staff.getPlatformAccountId());
        verify(accounts, never()).updateById(staff);
        verify(iam).revokeUserRole(801L);
    }

    @Test
    void bindImRejectsASecondStaffAccount() {
        var staff = new com.gvchat.platform.admin.infra.persistence.po.SaaAdminAccountPo();
        staff.setId(20L);
        staff.setPlatformAccountId(51L);
        when(imUsers.resolveOpenId("im-user")).thenReturn("im_150");
        when(identity.ensureAccount("IM", "im_150", "EMPLOYEE")).thenReturn(61L);
        when(accounts.selectCount(any())).thenReturn(1L);

        assertThrows(ApiException.class, () -> service.bindImAccount(staff, "im-user"));

        verifyNoInteractions(iam);
        verify(accounts, never()).updateById(any(com.gvchat.platform.admin.infra.persistence.po.SaaAdminAccountPo.class));
    }

    private StaffAccountApplicationService.CreateCommand command(String im, String password) {
        return new StaffAccountApplicationService.CreateCommand("staff1", password, im, "", 3L, "STORE", 100L, 101L, 102L);
    }

    // —— 换绑（旧 IM 账号已被删除）与显式解绑 ——

    /** 员工账号：id + 平台账号 + 当前 im_account。 */
    private SaaAdminAccountPo staffAccount(long id, long platformAccountId, String imAccount) {
        var staff = new SaaAdminAccountPo();
        staff.setId(id);
        staff.setPlatformAccountId(platformAccountId);
        staff.setImAccount(imAccount);
        return staff;
    }

    private TenantIamDomainClient.RoleBinding activeBinding() {
        return new TenantIamDomainClient.RoleBinding(
                3L, 100L, 101L, 102L, "store.manager", "Manager", "STORE", "Tenant", "Store", "ACTIVE");
    }

    /** 旧 IM 用户已不存在（IM 后台删除用户）：允许换绑，并照样迁移角色。 */
    @Test
    void rebindOverwritesImAccountWhenPreviousImUserIsGone() {
        var staff = staffAccount(20L, 51L, "old-user");
        when(imUsers.resolveOpenId("old-user")).thenReturn(null);
        when(imUsers.resolveOpenId("new-user")).thenReturn("im_150");
        when(identity.ensureAccount("IM", "im_150", "EMPLOYEE")).thenReturn(61L);
        when(iam.roleBindings(51L)).thenReturn(List.of(activeBinding()));
        when(iam.listUserRoleIds(61L)).thenReturn(List.of());
        when(iam.listUserRoleIds(51L)).thenReturn(List.of(701L));

        var result = service.bindImAccount(staff, "new-user");

        assertEquals(61L, result.getPlatformAccountId());
        assertEquals("new-user", result.getImAccount());
        verify(iam).assignUserRole(61L, 100L, 101L, 102L, 3L, "STORE");
        verify(iam).revokeUserRole(701L);
        verify(accounts).updateById(staff);
    }

    /** 旧 IM 身份在统一账号模型里已被清理（IM 用户行还在）：同样算「旧绑定失效」，允许换绑。 */
    @Test
    void rebindOverwritesImAccountWhenPreviousIdentityWasPurged() {
        var staff = staffAccount(20L, 51L, "old-user");
        when(imUsers.resolveOpenId("old-user")).thenReturn("im_140");
        when(identity.imIdentityExists("im_140")).thenReturn(false);
        when(imUsers.resolveOpenId("new-user")).thenReturn("im_150");
        when(identity.ensureAccount("IM", "im_150", "EMPLOYEE")).thenReturn(61L);
        when(iam.roleBindings(51L)).thenReturn(List.of(activeBinding()));
        when(iam.listUserRoleIds(61L)).thenReturn(List.of());
        when(iam.listUserRoleIds(51L)).thenReturn(List.of(701L));

        var result = service.bindImAccount(staff, "new-user");

        assertEquals(61L, result.getPlatformAccountId());
        assertEquals("new-user", result.getImAccount());
        verify(accounts).updateById(staff);
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
        assertEquals("old-user", staff.getImAccount());
        verify(identity, never()).ensureAccount(anyString(), anyString(), anyString());
        verify(accounts, never()).updateById(any(SaaAdminAccountPo.class));
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
        verify(accounts, never()).updateById(any(SaaAdminAccountPo.class));
    }

    /** 换绑时新 IM 账号已被另一个后台账号占用 → 仍必须是 409 STAFF_IM_ALREADY_BOUND。 */
    @Test
    void rebindRejectsWhenTargetImAccountIsTakenByAnotherStaff() {
        var staff = staffAccount(20L, 51L, "old-user");
        when(imUsers.resolveOpenId("old-user")).thenReturn(null);
        when(imUsers.resolveOpenId("new-user")).thenReturn("im_150");
        when(identity.ensureAccount("IM", "im_150", "EMPLOYEE")).thenReturn(61L);
        when(accounts.selectCount(any())).thenReturn(1L);

        ApiException failure = assertThrows(ApiException.class, () -> service.bindImAccount(staff, "new-user"));

        assertEquals(409, failure.getStatus());
        assertEquals("STAFF_IM_ALREADY_BOUND", failure.getCode());
        verifyNoInteractions(iam);
        verify(accounts, never()).updateById(any(SaaAdminAccountPo.class));
    }

    /** 解绑：显式把 im_account 置 NULL（默认 NOT_NULL 更新策略会静默漏掉这一列），账号与角色不动。 */
    @Test
    void unbindClearsImAccountWithExplicitNullSet() {
        var staff = staffAccount(20L, 51L, "im-user");

        var result = service.unbindImAccount(staff);

        assertNull(result.getImAccount());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<SaaAdminAccountPo>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(accounts).update(isNull(), captor.capture());
        LambdaUpdateWrapper<SaaAdminAccountPo> wrapper = captor.getValue();
        assertTrue(wrapper.getSqlSet().contains("im_account"), wrapper.getSqlSet());
        assertTrue(wrapper.getParamNameValuePairs().values().stream().anyMatch(Objects::isNull),
                "im_account 必须被显式置为 NULL，否则解绑会静默失效");
        verifyNoInteractions(iam);
    }

    /** 解绑幂等：本来就没绑定 → 直接返回，不发 UPDATE。 */
    @Test
    void unbindIsIdempotentWhenNothingBound() {
        var staff = staffAccount(20L, 51L, null);

        var result = service.unbindImAccount(staff);

        assertNull(result.getImAccount());
        verify(accounts, never()).update(any(), any());
    }
}

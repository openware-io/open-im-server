package com.gvchat.platform.admin.api.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.time.TimeRangeParams;
import com.gvchat.platform.admin.domain.model.AdminRole;
import com.gvchat.platform.admin.application.StaffAccountApplicationService;
import com.gvchat.platform.admin.infra.TenantIamDomainClient;
import com.gvchat.platform.admin.infra.persistence.mapper.SaaAdminAccountMapper;
import com.gvchat.platform.admin.infra.persistence.po.SaaAdminAccountPo;
import com.gvchat.platform.admin.infra.security.AdminContext;
import com.gvchat.platform.admin.infra.security.AdminContextHolder;
import com.gvchat.platform.admin.infra.security.AdminTenantContextTokenSigner;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StaffControllerTest {
    private final SaaAdminAccountMapper accounts = mock(SaaAdminAccountMapper.class);
    private final TenantIamDomainClient iam = mock(TenantIamDomainClient.class);
    private final AdminTenantContextTokenSigner tokens = mock(AdminTenantContextTokenSigner.class);
    private final StaffAccountApplicationService identity = mock(StaffAccountApplicationService.class);
    private final StaffController controller = new StaffController(accounts, iam, tokens, identity);

    @BeforeEach
    void setup() {
        // 纯单元测试没有 MyBatis 上下文：先注册 TableInfo，列表筛选的 LambdaQueryWrapper 才能解析列名。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SaaAdminAccountPo.class);
        AdminContextHolder.set(new AdminContext(1L, "admin", "admin", AdminRole.SUPER_ADMIN, 1L, "context"));
        when(tokens.verify("context")).thenReturn(new TenantContext(100L, null, null, 1L, 1));
        when(iam.permissions(1L, 100L, null, null)).thenReturn(new TenantIamDomainClient.PermissionSnapshot(
                1L, 100L, null, null, 1, List.of("iam.role.manage")));
    }

    @AfterEach
    void cleanup() { AdminContextHolder.clear(); }

    @Test
    void missingContextCannotReturnGlobalList() {
        AdminContextHolder.set(new AdminContext(1L, "admin", "admin", AdminRole.SUPER_ADMIN, 1L, null));
        assertThrows(ApiException.class, () -> controller.list(null, null));
        verifyNoInteractions(accounts);
    }

    /**
     * 运营人员列表的**创建时间**闭区间：条件落在 {@code created_at} 列上、日期两端收口到当天起点 /
     * 当天末尾（{@code 23:59:59.999}）；不传时间参数时不拼任何时间条件（列表行为不变）。
     */
    @Test
    void listAppliesClosedCreatedAtRangeAndTreatsBlankAsNoFilter() {
        when(accounts.selectList(any(Wrapper.class))).thenReturn(List.of());

        controller.list("2026-09-01", "2026-09-30");
        String sql = capturedListSql();
        assertTrue(sql.contains("created_at >="), sql);
        assertTrue(sql.contains("created_at <="), sql);
        assertFalse(sql.contains("DATE("), "时间列不得被函数包裹: " + sql);
        assertTrue(capturedListParams().contains(LocalDateTime.of(2026, 9, 1, 0, 0)), sql);
        assertTrue(capturedListParams().contains(LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000)), sql);

        controller.list(null, null);
        String unfiltered = capturedListSql();
        assertFalse(unfiltered.contains("created_at >="), unfiltered);
        assertFalse(unfiltered.contains("created_at <="), unfiltered);
    }

    /** from > to → 400 {@code TIME_RANGE_INVALID}（与全仓其它列表同一错误码），且不查库。 */
    @Test
    void listRejectsInvertedRangeWith400() {
        ApiException failure = assertThrows(ApiException.class, () -> controller.list("2026-09-30", "2026-09-01"));

        assertEquals(400, failure.getStatus());
        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
        assertEquals(TimeRangeParams.MESSAGE_TIME_RANGE_INVALID, failure.getMessage());
        verifyNoInteractions(accounts);
    }

    @Test
    void listRejectsMalformedTimeWith400() {
        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID,
                assertThrows(ApiException.class, () -> controller.list("2026/09/01", null)).getCode());
        verifyNoInteractions(accounts);
    }

    private String capturedListSql() {
        return capturedListWrapper().getSqlSegment();
    }

    private java.util.Collection<Object> capturedListParams() {
        return capturedListWrapper().getParamNameValuePairs().values();
    }

    @SuppressWarnings("unchecked")
    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SaaAdminAccountPo> capturedListWrapper() {
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SaaAdminAccountPo>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(accounts, atLeastOnce()).selectList(captor.capture());
        return captor.getValue();
    }

    @Test
    void listFiltersBothAccountsAndRoleDetails() {
        var shared = account(10L);
        var other = account(20L);
        when(accounts.selectList(any(Wrapper.class))).thenReturn(List.of(shared, other));
        var local = binding(100L);
        var foreign = binding(200L);
        when(iam.roleBindings(10L)).thenReturn(List.of(local, foreign));
        when(iam.roleBindings(20L)).thenReturn(List.of(foreign));
        var result = controller.list(null, null);
        assertEquals(1, result.size());
        assertEquals(List.of(local), result.getFirst().roles());
    }

    @Test
    void foreignOrSharedAccountCannotBeDeletedOrDisabled() {
        when(accounts.selectById(10L)).thenReturn(account(10L));
        when(iam.roleBindings(10L)).thenReturn(List.of(binding(100L), binding(200L)));
        assertThrows(ApiException.class, () -> controller.delete(10L));
        assertThrows(ApiException.class, () -> controller.updateStatus(10L, new StaffController.UpdateStatusRequest("DISABLED")));
        assertThrows(ApiException.class, () -> controller.unbindImAccount(10L));
        verify(iam, never()).listUserRoleIds(anyLong());
        verify(accounts, never()).deleteById(anyLong());
    }

    @Test
    void cannotCreateStaffInAnotherTenant() {
        assertThrows(ApiException.class, () -> controller.create(new StaffController.CreateStaffRequest(
                "test-user", "TestPass123", null, "Test", 2L, "TENANT", 200L, null, null)));
        verifyNoInteractions(identity, accounts);
    }

    @Test
    void revokedManagementPermissionIsRejected() {
        when(iam.permissions(1L, 100L, null, null)).thenReturn(new TenantIamDomainClient.PermissionSnapshot(
                1L, 100L, null, null, 2, List.of()));
        assertThrows(ApiException.class, () -> controller.list(null, null));
        verifyNoInteractions(accounts);
    }

    @Test
    void storeContextFiltersOtherStoresAndBlocksSharedAccountMutation() {
        when(tokens.verify("context")).thenReturn(new TenantContext(100L, 101L, 102L, 1L, 1));
        when(iam.permissions(1L, 100L, 101L, 102L)).thenReturn(new TenantIamDomainClient.PermissionSnapshot(
                1L, 100L, 101L, 102L, 1, List.of("iam.role.manage")));
        var local = new TenantIamDomainClient.RoleBinding(3L, 100L, 101L, 102L, "store.manager", "Manager", "STORE", "Tenant", "Local", "ACTIVE");
        var other = new TenantIamDomainClient.RoleBinding(3L, 100L, 101L, 103L, "store.manager", "Manager", "STORE", "Tenant", "Other", "ACTIVE");
        when(accounts.selectList(any(Wrapper.class))).thenReturn(List.of(account(10L), account(20L)));
        when(accounts.selectById(10L)).thenReturn(account(10L));
        when(iam.roleBindings(10L)).thenReturn(List.of(local, other));
        when(iam.roleBindings(20L)).thenReturn(List.of(other));
        var result = controller.list(null, null);
        assertEquals(1, result.size());
        assertEquals(List.of(local), result.getFirst().roles());
        assertFalse(result.getFirst().canManageAccount());
        assertThrows(ApiException.class, () -> controller.delete(10L));
        assertThrows(ApiException.class, () -> controller.create(new StaffController.CreateStaffRequest(
                "test-user", "TestPass123", null, "Test", 2L, "TENANT", 100L, 101L, null)));
        verifyNoInteractions(identity);
    }

    @Test
    void roleLookupFailureDoesNotMasqueradeAsEmptyList() {
        when(accounts.selectList(any(Wrapper.class))).thenReturn(List.of(account(10L)));
        when(iam.roleBindings(10L)).thenThrow(new IllegalStateException("IAM unavailable"));
        assertThrows(IllegalStateException.class, () -> controller.list(null, null));
    }

    @Test
    void failedRevocationDoesNotReportDeletedAccount() {
        when(accounts.selectById(10L)).thenReturn(account(10L));
        when(iam.roleBindings(10L)).thenReturn(List.of(binding(100L)));
        when(iam.listUserRoleIds(10L)).thenReturn(List.of(50L));
        doThrow(new IllegalStateException("IAM unavailable")).when(iam).revokeUserRole(50L);
        assertThrows(IllegalStateException.class, () -> controller.delete(10L));
        verify(accounts, never()).deleteById(anyLong());
    }

    private SaaAdminAccountPo account(long id) {
        var account = new SaaAdminAccountPo();
        account.setId(id);
        account.setPlatformAccountId(id);
        account.setRole("TENANT_ADMIN");
        return account;
    }

    @Test
    void tenantOwnerCanAssignStaffToSelectedStoreWithoutExposingPasswordHash() throws Exception {
        when(iam.isStaffRole(3L, "STORE")).thenReturn(true);
        when(iam.staffStores(any())).thenReturn(List.of(new TenantIamDomainClient.StaffStore(102L, 101L, "Store")));
        var created = account(25L);
        created.setPasswordHash("secret-hash");
        when(identity.create(any())).thenReturn(created);
        var result = controller.create(new StaffController.CreateStaffRequest("staff1", "TestPass123", null,
                "Staff", 3L, "STORE", 100L, 101L, 102L));
        assertEquals(25L, result.platformAccountId());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
        assertFalse(json.contains("password"));
        assertFalse(json.contains("secret-hash"));
    }

    @Test
    void bindImDelegatesOnlyAfterScopeValidation() {
        var staff = account(10L);
        var rebound = account(10L);
        rebound.setPlatformAccountId(61L);
        rebound.setImAccount("im-user");
        when(accounts.selectById(10L)).thenReturn(staff);
        when(iam.roleBindings(10L)).thenReturn(List.of(binding(100L)));
        when(identity.bindImAccount(staff, "im-user")).thenReturn(rebound);

        var result = controller.bindImAccount(10L, new StaffController.BindImAccountRequest("im-user"));

        assertEquals(61L, result.platformAccountId());
        assertTrue(result.imBound());
    }

    /** 解绑端点：作用域校验通过后才委派应用服务；返回体只暴露 imBound，不带任何 IM 标识明文。 */
    @Test
    void unbindImDelegatesOnlyAfterScopeValidation() {
        var staff = account(10L);
        staff.setImAccount("im-user");
        var unbound = account(10L);
        when(accounts.selectById(10L)).thenReturn(staff);
        when(iam.roleBindings(10L)).thenReturn(List.of(binding(100L)));
        when(identity.unbindImAccount(staff)).thenReturn(unbound);

        var result = controller.unbindImAccount(10L);

        assertFalse(result.imBound());
        assertEquals(10L, result.platformAccountId());
        verify(identity).unbindImAccount(staff);
    }

    @Test
    void foreignStoreAndMismatchedOrganizationAreRejectedBeforeProvisioning() {
        when(iam.isStaffRole(3L, "STORE")).thenReturn(true);
        when(iam.staffStores(any())).thenReturn(List.of(new TenantIamDomainClient.StaffStore(102L, 101L, "Store")));
        assertThrows(ApiException.class, () -> controller.create(new StaffController.CreateStaffRequest("staff1", "TestPass123", null,
                "Staff", 3L, "STORE", 100L, 101L, 999L)));
        assertThrows(ApiException.class, () -> controller.create(new StaffController.CreateStaffRequest("staff1", "TestPass123", null,
                "Staff", 3L, "STORE", 100L, 999L, 102L)));
        verifyNoInteractions(identity);
    }

    private TenantIamDomainClient.RoleBinding binding(long tenantId) {
        return new TenantIamDomainClient.RoleBinding(2L, tenantId, null, null, "tenant.owner", "Owner", "TENANT", "Tenant", null, "ACTIVE");
    }
}

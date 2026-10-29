package io.openware.platform.admin.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.platform.admin.application.StaffAccountApplicationService;
import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.domain.model.ConsAdminAccount;
import io.openware.platform.admin.infra.TenantIamDomainClient;
import io.openware.platform.admin.infra.security.AdminContext;
import io.openware.platform.admin.infra.security.AdminContextHolder;
import io.openware.platform.admin.infra.security.AdminTenantContextTokenSigner;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 运营人员端点的**入站适配**行为：上下文提取与鉴权、参数口径校验、委派应用服务、响应形状。
 *
 * <p>此前这些用例断言的是控制器自己拼出的 {@code LambdaQueryWrapper}（SQL 片段与参数），等于把持久化
 * 实现写进了控制器测试。列表过滤与作用域判定已下移到应用服务与领域端口，相应地那些断言也随之归属
 * 应用服务与其端口测试——这里只验证控制器真正负责的部分。
 */
class StaffControllerTest {
    private final TenantIamDomainClient iam = mock(TenantIamDomainClient.class);
    private final AdminTenantContextTokenSigner tokens = mock(AdminTenantContextTokenSigner.class);
    private final StaffAccountApplicationService staffAccounts = mock(StaffAccountApplicationService.class);
    private final StaffController controller = new StaffController(iam, tokens, staffAccounts);

    @BeforeEach
    void setup() {
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
        verifyNoInteractions(staffAccounts);
    }

    /**
     * 时间区间仍由控制器解析并**原样传给应用服务**：日期两端收口到当天起点与当天末尾
     * （{@code 23:59:59.999}）；为空时不设边界；下推到 SQL 的过滤在应用服务/端口完成。
     */
    @Test
    void listPassesClosedCreatedAtRangeAndTreatsBlankAsNoFilter() {
        when(staffAccounts.listStaff(any(), any(), any())).thenReturn(List.of());

        controller.list("2026-09-01", "2026-09-30");
        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(staffAccounts).listStaff(any(), from.capture(), to.capture());
        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), from.getValue());
        assertEquals(LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000), to.getValue());

        // 不传时间参数：两端都不设边界（区间解析为空值即为"不筛"）
        controller.list(null, null);
        ArgumentCaptor<LocalDateTime> blankFrom = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> blankTo = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(staffAccounts, times(2)).listStaff(any(), blankFrom.capture(), blankTo.capture());
        assertNull(blankFrom.getAllValues().get(1));
        assertNull(blankTo.getAllValues().get(1));
    }

    /** from > to → 400 {@code TIME_RANGE_INVALID}（与全仓其它列表同一错误码），且不调用应用服务。 */
    @Test
    void listRejectsInvertedRangeWith400() {
        ApiException failure = assertThrows(ApiException.class, () -> controller.list("2026-09-30", "2026-09-01"));

        assertEquals(400, failure.getStatus());
        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
        assertEquals(TimeRangeParams.MESSAGE_TIME_RANGE_INVALID, failure.getMessage());
        verifyNoInteractions(staffAccounts);
    }

    @Test
    void listRejectsMalformedTimeWith400() {
        assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID,
                assertThrows(ApiException.class, () -> controller.list("2026/09/01", null)).getCode());
        verifyNoInteractions(staffAccounts);
    }

    @Test
    void listMapsApplicationResultToResponse() {
        var binding = binding(100L);
        when(staffAccounts.listStaff(any(), any(), any())).thenReturn(List.of(
                new StaffAccountApplicationService.StaffListItem(10L, "staff1", "Staff", 10L, true,
                        "TENANT_ADMIN", "ACTIVE", LocalDateTime.of(2026, 1, 1, 0, 0), List.of(binding), true)));

        var result = controller.list(null, null);

        assertEquals(1, result.size());
        assertEquals(10L, result.getFirst().id());
        assertTrue(result.getFirst().imBound());
        assertEquals(List.of(binding), result.getFirst().roles());
    }

    /** 撤销管理权限后不再放行列表（鉴权上下文校验在控制器）。 */
    @Test
    void revokedManagementPermissionIsRejected() {
        when(iam.permissions(1L, 100L, null, null)).thenReturn(new TenantIamDomainClient.PermissionSnapshot(
                1L, 100L, null, null, 2, List.of()));
        assertThrows(ApiException.class, () -> controller.list(null, null));
        verifyNoInteractions(staffAccounts);
    }

    @Test
    void cannotCreateStaffInAnotherTenant() {
        assertThrows(ApiException.class, () -> controller.create(new StaffController.CreateStaffRequest(
                "test-user", "TestPass123", null, "Test", 2L, "TENANT", 200L, null, null)));
        verifyNoInteractions(staffAccounts);
    }

    @Test
    void foreignStoreAndMismatchedOrganizationAreRejectedBeforeProvisioning() {
        when(iam.isStaffRole(3L, "STORE")).thenReturn(true);
        when(iam.staffStores(any())).thenReturn(List.of(new TenantIamDomainClient.StaffStore(102L, 101L, "Store")));
        assertThrows(ApiException.class, () -> controller.create(new StaffController.CreateStaffRequest("staff1", "TestPass123", null,
                "Staff", 3L, "STORE", 100L, 101L, 999L)));
        assertThrows(ApiException.class, () -> controller.create(new StaffController.CreateStaffRequest("staff1", "TestPass123", null,
                "Staff", 3L, "STORE", 100L, 999L, 102L)));
        verifyNoInteractions(staffAccounts);
    }

    /** 创建响应不得泄露密码哈希：控制器只映射对外视图字段。 */
    @Test
    void tenantOwnerCanAssignStaffToSelectedStoreWithoutExposingPasswordHash() throws Exception {
        when(iam.isStaffRole(3L, "STORE")).thenReturn(true);
        when(iam.staffStores(any())).thenReturn(List.of(new TenantIamDomainClient.StaffStore(102L, 101L, "Store")));
        when(staffAccounts.create(any())).thenReturn(account(25L, 25L, null, "secret-hash"));

        var result = controller.create(new StaffController.CreateStaffRequest("staff1", "TestPass123", null,
                "Staff", 3L, "STORE", 100L, 101L, 102L));

        assertEquals(25L, result.platformAccountId());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
        assertFalse(json.contains("password"));
        assertFalse(json.contains("secret-hash"));
    }

    /**
     * 组织级上下文的 storeId 落库口径是 0（iam_role_binding.store_id NOT NULL DEFAULT 0）。
     * 它必须被归一成“不限门店”，否则门店下拉为空、开通门店角色会被误判为跨门店 403。
     */
    @Test
    void organizationContextWithZeroStoreIdCanProvisionStoreStaff() {
        when(tokens.verify("context")).thenReturn(new TenantContext(100L, 100L, 0L, 1L, 1));
        when(iam.permissions(1L, 100L, 100L, 0L)).thenReturn(new TenantIamDomainClient.PermissionSnapshot(
                1L, 100L, 100L, 0L, 1, List.of("iam.role.manage")));
        when(iam.isStaffRole(3L, "STORE")).thenReturn(true);
        when(iam.staffStores(any())).thenReturn(List.of(new TenantIamDomainClient.StaffStore(102L, 100L, "Store")));
        when(staffAccounts.create(any())).thenReturn(account(25L, 25L, null, "secret-hash"));

        var result = controller.create(new StaffController.CreateStaffRequest("staff1", "TestPass123", null,
                "Staff", 3L, "STORE", 100L, 100L, 102L));

        assertEquals(25L, result.platformAccountId());
        ArgumentCaptor<TenantContext> scoped = ArgumentCaptor.forClass(TenantContext.class);
        verify(iam).staffStores(scoped.capture());
        assertNull(scoped.getValue().storeId(), "组织级上下文的 storeId=0 必须归一为 null");
        assertEquals(100L, scoped.getValue().organizationId());
    }

    /** 组织级上下文的门店下拉：0 不能被当成门店过滤条件，否则返回空列表。 */
    @Test
    void optionsInOrganizationContextTreatZeroStoreIdAsUnscoped() {
        when(tokens.verify("context")).thenReturn(new TenantContext(100L, 100L, 0L, 1L, 1));
        when(iam.permissions(1L, 100L, 100L, 0L)).thenReturn(new TenantIamDomainClient.PermissionSnapshot(
                1L, 100L, 100L, 0L, 1, List.of("iam.role.manage")));
        when(iam.staffRoles()).thenReturn(List.of(
                new TenantIamDomainClient.StaffRole(3L, "store.manager", "店长", "STORE")));
        when(iam.staffStores(any())).thenReturn(List.of(new TenantIamDomainClient.StaffStore(102L, 100L, "Store")));

        var options = controller.options();

        assertEquals(1, options.stores().size());
        ArgumentCaptor<TenantContext> scoped = ArgumentCaptor.forClass(TenantContext.class);
        verify(iam).staffStores(scoped.capture());
        assertNull(scoped.getValue().storeId());
        assertEquals(100L, scoped.getValue().organizationId());
    }

    /** 作用域校验通过后才委派；控制器不再自己查库。 */
    @Test
    void bindImDelegatesOnlyAfterScopeValidation() {
        var staff = account(10L, 10L, null, "hash");
        var rebound = account(10L, 61L, "im-user", "hash");
        when(staffAccounts.requireManageableStaff(any(), eq(10L))).thenReturn(staff);
        when(staffAccounts.bindImAccount(staff, "im-user")).thenReturn(rebound);

        var result = controller.bindImAccount(10L, new StaffController.BindImAccountRequest("im-user"));

        assertEquals(61L, result.platformAccountId());
        assertTrue(result.imBound());
    }

    /** 解绑端点：作用域校验通过后才委派；返回体只暴露 imBound，不带任何 IM 标识明文。 */
    @Test
    void unbindImDelegatesOnlyAfterScopeValidation() {
        var staff = account(10L, 10L, "im-user", "hash");
        var unbound = account(10L, 10L, null, "hash");
        when(staffAccounts.requireManageableStaff(any(), eq(10L))).thenReturn(staff);
        when(staffAccounts.unbindImAccount(staff)).thenReturn(unbound);

        var result = controller.unbindImAccount(10L);

        assertFalse(result.imBound());
        assertEquals(10L, result.platformAccountId());
        verify(staffAccounts).unbindImAccount(staff);
    }

    @Test
    void deleteDelegatesToApplicationService() {
        controller.delete(10L);
        verify(staffAccounts).deleteStaff(any(), eq(10L));
    }

    private ConsAdminAccount account(long id, long platformAccountId, String imAccount, String passwordHash) {
        return new ConsAdminAccount(id, "staff" + id, passwordHash, "Staff", imAccount, null,
                platformAccountId, AdminRole.TENANT_ADMIN, ConsAdminAccount.STATUS_ACTIVE);
    }

    private TenantIamDomainClient.RoleBinding binding(long tenantId) {
        return new TenantIamDomainClient.RoleBinding(2L, tenantId, null, null, "tenant.owner", "Owner", "TENANT", "Tenant", null, "ACTIVE");
    }
}

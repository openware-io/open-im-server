package io.openware.platform.admin.infra;

import io.openware.infrastructure.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运营人员门店下拉的范围判定。
 *
 * <p>回归背景：组织/租户级上下文的 {@code storeId} 在 {@code iam_role_binding} 里落库为
 * {@code 0}（NOT NULL DEFAULT 0），而门店下拉按上下文过滤门店。旧实现只判断 null，
 * 于是 {@code 0} 被当成“门店 0”，把所有门店都过滤掉——表现为“新增运营人员时门店下拉无数据”。
 */
class TenantIamDomainClientScopeTest {

    @Test
    void organizationContextWithZeroStoreIdMatchesAllStoresInOrganization() {
        TenantContext organizationContext = new TenantContext(100L, 100L, 0L, 1L, 1);

        assertTrue(TenantIamDomainClient.matchesScope(organizationContext, 100L, 100L, 100L));
        assertTrue(TenantIamDomainClient.matchesScope(organizationContext, 100L, 100L, 102L));
        assertFalse(TenantIamDomainClient.matchesScope(organizationContext, 100L, 101L, 102L));
        assertFalse(TenantIamDomainClient.matchesScope(organizationContext, 200L, 100L, 102L));
    }

    @Test
    void tenantHeadquartersContextWithZeroOrganizationAndStoreMatchesAllTenantStores() {
        TenantContext tenantContext = new TenantContext(100L, 0L, 0L, 1L, 1);

        assertTrue(TenantIamDomainClient.matchesScope(tenantContext, 100L, 100L, 102L));
        assertTrue(TenantIamDomainClient.matchesScope(tenantContext, 100L, 101L, 103L));
        assertFalse(TenantIamDomainClient.matchesScope(tenantContext, 200L, 100L, 102L));
    }

    @Test
    void storeContextOnlyMatchesCurrentStore() {
        TenantContext storeContext = new TenantContext(100L, 100L, 102L, 1L, 1);

        assertTrue(TenantIamDomainClient.matchesScope(storeContext, 100L, 100L, 102L));
        assertFalse(TenantIamDomainClient.matchesScope(storeContext, 100L, 100L, 103L));
    }
}

package com.gvchat.platform.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.platform.identity.api.dto.AuthContextDtos.ContextItem;
import com.gvchat.platform.identity.api.dto.AuthContextDtos.SelectContextResponse;
import com.gvchat.platform.identity.infra.client.TenantServiceClient;
import com.gvchat.platform.identity.infra.security.TenantContextTokenSigner;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 经营上下文选择：调用 tenant-service 内部端点并保留可诊断的失败语义。 */
class AuthContextApplicationServiceTest {

  private final TenantServiceClient tenantClient = mock(TenantServiceClient.class);
  private final TenantContextTokenSigner tokenSigner = mock(TenantContextTokenSigner.class);
  private final AuthContextApplicationService service =
      new AuthContextApplicationService(tenantClient, tokenSigner);

  @Test
  void contexts_mapsTenantContextsWithRolesAndScope() {
    when(tenantClient.contexts(100L)).thenReturn(List.of(
        new TenantServiceClient.TenantContext("1:10:1001", 1L, "Tenant A", 10L, "Org A", 1001L, "Store A1",
            List.of("STORE_MANAGER", "CASHIER"), "STORE"),
        new TenantServiceClient.TenantContext("2::", 2L, "Tenant B", null, null, null, null,
            List.of("TENANT_ADMIN"), "TENANT")));

    List<ContextItem> result = service.contexts(100L);

    assertIterableEquals(List.of(
        new ContextItem("1:10:1001", 1L, "Tenant A", 10L, "Org A", 1001L, "Store A1",
            List.of("STORE_MANAGER", "CASHIER"), "STORE"),
        new ContextItem("2::", 2L, "Tenant B", null, null, null, null,
            List.of("TENANT_ADMIN"), "TENANT")), result);
  }

  @Test
  void contexts_returnsEmptyForNullAccount() {
    assertIterableEquals(List.of(), service.contexts(null));
    verify(tenantClient, never()).contexts(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void contexts_returnsRegisteredConsumerContextWhenOperatorContextsAreEmpty() {
    when(tenantClient.contexts(158L)).thenReturn(List.of());
    when(tenantClient.consumerContext(158L, "saas-a380-c")).thenReturn(
        new TenantServiceClient.TenantContext("consumer:saas-a380-c:100:100:100", 100L, "A380",
            100L, "总部", 100L, "首店", List.of("CONSUMER"), "CONSUMER"));

    List<ContextItem> result = service.contexts(158L, "saas-a380-c");

    assertEquals(1, result.size());
    assertEquals("CONSUMER", result.get(0).scopeType());
  }

  @Test
  void contexts_prefersRegisteredConsumerContextOverOperatorContexts() {
    when(tenantClient.contexts(158L)).thenReturn(List.of(
        new TenantServiceClient.TenantContext("2::", 2L, "Operations", null, null, null, null,
            List.of("TENANT_ADMIN"), "TENANT")));
    when(tenantClient.consumerContext(158L, "saas-a380-c")).thenReturn(
        new TenantServiceClient.TenantContext("consumer:saas-a380-c:100:100:100", 100L, "A380",
            100L, "总部", 100L, "首店", List.of("CONSUMER"), "CONSUMER"));

    List<ContextItem> result = service.contexts(158L, "saas-a380-c");

    assertEquals(1, result.size());
    assertEquals("consumer:saas-a380-c:100:100:100", result.get(0).contextId());
    assertEquals("CONSUMER", result.get(0).scopeType());
  }

  @Test
  void operatorApp_registeredOperator_returnsOperatorContextScopedToAppAnchor() {
    // saas-a380-h5（B 端/后台）：IM 账号已在 SaaS 租户后台注册为运营人员（store.manager@A380/首店）
    when(tenantClient.consumerContext(158L, "saas-a380-h5")).thenReturn(
        new TenantServiceClient.TenantContext("consumer:saas-a380-h5:100:100:100", 100L, "A380",
            100L, "总部", 100L, "首店", List.of("CONSUMER"), "CONSUMER"));
    when(tenantClient.contexts(158L)).thenReturn(List.of(
        new TenantServiceClient.TenantContext("100:100:100", 100L, "A380", 100L, "总部", 100L, "首店",
            List.of("store.manager"), "STORE")));

    List<ContextItem> result = service.contexts(158L, "saas-a380-h5");

    assertEquals(1, result.size());
    assertEquals("100:100:100", result.get(0).contextId());
    assertIterableEquals(List.of("store.manager"), result.get(0).roles());
    verify(tenantClient).contexts(158L);
  }

  @Test
  void operatorApp_unregisteredOperator_returnsEmptyContexts() {
    // 未注册运营人员：有 consumer 授权锚点但没有任何运营角色上下文 → 空 → B 端显示无权限
    when(tenantClient.consumerContext(158L, "saas-a380-h5")).thenReturn(
        new TenantServiceClient.TenantContext("consumer:saas-a380-h5:100:100:100", 100L, "A380",
            100L, "总部", 100L, "首店", List.of("CONSUMER"), "CONSUMER"));
    when(tenantClient.contexts(158L)).thenReturn(List.of(
        new TenantServiceClient.TenantContext("100:100:100", 100L, "A380", 100L, "总部", 100L, "首店",
            List.of("CONSUMER"), "CONSUMER")));

    List<ContextItem> result = service.contexts(158L, "saas-a380-h5");

    assertEquals(0, result.size());
    verify(tenantClient).contexts(158L);
  }

  @Test
  void operatorApp_missingAnchor_returnsEmptyContexts() {
    when(tenantClient.consumerContext(158L, "saas-a380-h5")).thenReturn(null);

    List<ContextItem> result = service.contexts(158L, "saas-a380-h5");

    assertEquals(0, result.size());
  }

  @Test
  void operatorApp_filtersContextsOutsideAnchorTenantOrStore() {
    when(tenantClient.consumerContext(158L, "saas-a380-h5")).thenReturn(
        new TenantServiceClient.TenantContext("consumer:saas-a380-h5:100:100:100", 100L, "A380",
            100L, "总部", 100L, "首店", List.of("CONSUMER"), "CONSUMER"));
    when(tenantClient.contexts(158L)).thenReturn(List.of(
        new TenantServiceClient.TenantContext("999::", 999L, "Other", null, null, null, null,
            List.of("tenant.owner"), "TENANT"),
        new TenantServiceClient.TenantContext("100:100:999", 100L, "A380", 100L, "总部", 999L, "其它门店",
            List.of("store.manager"), "STORE")));

    List<ContextItem> result = service.contexts(158L, "saas-a380-h5");

    assertEquals(0, result.size());
  }

  @Test
  void contexts_raisesServiceUnavailableWhenTenantUnavailable() {
    when(tenantClient.contexts(100L)).thenThrow(new IllegalStateException("tenant down"));

    var exception = assertThrows(com.gvchat.common.exception.ApiException.class,
        () -> service.contexts(100L));
    assertEquals("IAM_CONTEXT_UNAVAILABLE", exception.getCode());
  }

  @Test
  void select_requiresAccountAndContextId() {
    assertThrows(IllegalArgumentException.class, () -> service.select(null, "1:10:1001"));
    assertThrows(IllegalArgumentException.class, () -> service.select(100L, null));
    assertThrows(IllegalArgumentException.class, () -> service.select(100L, "  "));
  }

  @Test
  void select_signsTokenWithScopedSnapshot() {
    TenantServiceClient.PermissionSnapshot snapshot = new TenantServiceClient.PermissionSnapshot(
        100L, 1L, 10L, 1001L, 7, List.of("a", "b"));
    when(tenantClient.permissions(100L, 1L, 10L, 1001L, true)).thenReturn(snapshot);
    when(tenantClient.tenantCurrencyCode(1L)).thenReturn("CNY");
    when(tokenSigner.sign(100L, 1L, 10L, 1001L, 7, List.of("a", "b"), "CNY")).thenReturn("signed-token");

    SelectContextResponse result = service.select(100L, "1:10:1001");

    assertEquals("signed-token", result.tenantContextToken());
    assertEquals("CNY", result.currencyCode());
    assertEquals(1L, result.tenantId());
    assertEquals(10L, result.organizationId());
    assertEquals(1001L, result.storeId());
    assertEquals(7, result.authorizationVersion());
    assertIterableEquals(List.of("a", "b"), result.permissions());
    long now = System.currentTimeMillis() / 1000;
    assertTrue(result.expiresAt() >= now + 1799 && result.expiresAt() <= now + 1801,
        "expiresAt should be ~30 minutes from now");
  }

  @Test
  void select_parsesTenantOnlyContextIdWithNullOrgAndStore() {
    TenantServiceClient.PermissionSnapshot snapshot = new TenantServiceClient.PermissionSnapshot(
        100L, 2L, null, null, 1, List.of("tenant.read"));
    when(tenantClient.permissions(100L, 2L, null, null, true)).thenReturn(snapshot);
    when(tokenSigner.sign(100L, 2L, null, null, 1, List.of("tenant.read"), "USD")).thenReturn("signed-token");

    SelectContextResponse result = service.select(100L, "2::");

    assertEquals("signed-token", result.tenantContextToken());
    // 未配置币种（tenantCurrencyCode 返回 null）时必须签发 USD
    assertEquals("USD", result.currencyCode());
    assertEquals(2L, result.tenantId());
    assertEquals(null, result.organizationId());
    assertEquals(null, result.storeId());
  }

  @Test
  void select_signsRegisteredConsumerContext() {
    when(tenantClient.consumerPermissions(158L, "saas-a380-c", "consumer:saas-a380-c:100:100:100"))
        .thenReturn(new TenantServiceClient.PermissionSnapshot(158L, 100L, 100L, 100L, 1,
            List.of("reservation.view", "reservation.create")));
    when(tokenSigner.sign(158L, 100L, 100L, 100L, 1,
        List.of("reservation.view", "reservation.create"), "USD")).thenReturn("consumer-token");

    SelectContextResponse result = service.select(158L, "consumer:saas-a380-c:100:100:100", "saas-a380-c");

    assertEquals("consumer-token", result.tenantContextToken());
    assertEquals(100L, result.storeId());
  }

  @Test
  void select_rejectsContextWhenIamReturnsNoPermissions() {
    when(tenantClient.permissions(100L, 2L, null, null, true)).thenReturn(
        new TenantServiceClient.PermissionSnapshot(100L, 2L, null, null, 0, List.of()));

    var exception = assertThrows(com.gvchat.common.exception.ApiException.class,
        () -> service.select(100L, "2::"));

    assertEquals("IAM_CONTEXT_FORBIDDEN", exception.getCode());
    verify(tokenSigner, never()).sign(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void select_rejectsSnapshotForDifferentAccountOrScope() {
    when(tenantClient.permissions(100L, 1L, 10L, 1001L, true)).thenReturn(
        new TenantServiceClient.PermissionSnapshot(101L, 9L, 90L, 9001L, 1, List.of("a")));

    var exception = assertThrows(com.gvchat.common.exception.ApiException.class,
        () -> service.select(100L, "1:10:1001"));

    assertEquals("IAM_CONTEXT_FORBIDDEN", exception.getCode());
  }

  @Test
  void select_raisesServiceUnavailableWhenTenantUnavailable() {
    when(tenantClient.permissions(100L, 1L, 10L, 1001L, true))
        .thenThrow(new IllegalStateException("tenant down"));

    var exception = assertThrows(com.gvchat.common.exception.ApiException.class,
        () -> service.select(100L, "1:10:1001"));
    assertEquals("IAM_CONTEXT_UNAVAILABLE", exception.getCode());
  }

  @Test
  void select_rejectsMalformedContextId() {
    var exception = assertThrows(com.gvchat.common.exception.ApiException.class,
        () -> service.select(100L, "not-a-context"));
    assertEquals("IAM_CONTEXT_INVALID", exception.getCode());
  }
}

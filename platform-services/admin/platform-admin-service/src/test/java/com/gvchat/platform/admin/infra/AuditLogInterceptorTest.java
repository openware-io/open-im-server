package com.gvchat.platform.admin.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.admin.domain.model.AdminRole;
import com.gvchat.platform.admin.infra.security.AdminContext;
import com.gvchat.platform.admin.infra.security.AdminContextHolder;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 后台操作审计拦截器回归：
 * 写操作落库（动作码/操作人/资源）、读操作不落库、失败也记 FAILURE + 错误码、上传与导出特殊路径覆盖。
 */
class AuditLogInterceptorTest {

  private final AuditClient auditClient = mock(AuditClient.class);
  private final AuditLogInterceptor interceptor = new AuditLogInterceptor(auditClient);

  @BeforeEach
  void setUp() {
    AdminContextHolder.set(new AdminContext(1L, "admin", "平台超管", AdminRole.SUPER_ADMIN, 88L, "token"));
    TenantContextHolder.set(new TenantContext(1001L, 20L, 30L, 88L, 3, List.of("audit.view")));
  }

  @AfterEach
  void cleanup() {
    AdminContextHolder.clear();
    TenantContextHolder.clear();
  }

  @Test
  void recordsSuccessfulWriteWithPlatformOperatorAndAction() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/staff");
    request.addHeader("X-Request-Id", "req-777");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(200);

    run(request, response, null);

    AuditClient.AuditRecord record = capture();
    assertEquals("staff.create", record.action());
    assertEquals("员工新建", record.actionLabel());
    assertEquals("staff", record.resourceType());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    assertEquals(AuditClient.AuditRecord.OPERATOR_TYPE_PLATFORM, record.operatorType());
    assertEquals(88L, record.operatorId());
    assertEquals("平台超管", record.operatorName());
    assertEquals("admin", record.operatorAccount());
    assertEquals(1001L, record.tenantId());
    assertEquals(20L, record.organizationId());
    assertEquals(30L, record.storeId());
    assertEquals("req-777", record.requestId());
    assertTrue(record.detailJson().contains("\"/admin/staff\""));
    assertNull(record.errorCode());
  }

  @Test
  void readRequestsAreNotAudited() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/menus");
    MockHttpServletResponse response = new MockHttpServletResponse();

    run(request, response, null);

    verify(auditClient, never()).recordAsync(any());
  }

  @Test
  void failedWriteIsRecordedAsFailureWithErrorCode() {
    MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/admin/staff/12/status");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(403);

    run(request, response, new ApiException(403, "PERMISSION_DENIED", "缺少权限"));

    AuditClient.AuditRecord record = capture();
    assertEquals("staff.update", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("PERMISSION_DENIED", record.errorCode());
  }

  @Test
  void serverErrorWithoutBusinessCodeFallsBackToHttpStatus() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/ktv/pricing-plans");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(500);

    run(request, response, new IllegalStateException("boom"));

    AuditClient.AuditRecord record = capture();
    assertEquals("pricing-plan.create", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("IllegalStateException", record.errorCode());
  }

  @Test
  void imageUploadAndSensitiveExportAreCovered() {
    MockHttpServletRequest upload = new MockHttpServletRequest("POST", "/admin/media/images");
    run(upload, new MockHttpServletResponse(), null);
    assertEquals("media.image.upload", capture().action());

    reset(auditClient);
    MockHttpServletRequest export = new MockHttpServletRequest("GET", "/admin/reports/export");
    run(export, new MockHttpServletResponse(), null);
    assertEquals("report.export", capture().action());
  }

  @Test
  void tenantAdminIsRecordedAsTenantOperator() {
    AdminContextHolder.set(new AdminContext(5L, "owner", "租户老板", AdminRole.TENANT_ADMIN, null, "token"));
    MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/admin/staff/9");

    run(request, new MockHttpServletResponse(), null);

    AuditClient.AuditRecord record = capture();
    assertEquals("staff.delete", record.action());
    assertEquals(AuditClient.AuditRecord.OPERATOR_TYPE_TENANT, record.operatorType());
    assertEquals(5L, record.operatorId());
  }

  @Test
  void nonAdminPathsAreIgnored() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/admin/whatever");

    run(request, new MockHttpServletResponse(), null);

    verify(auditClient, never()).recordAsync(any());
  }

  private void run(MockHttpServletRequest request, MockHttpServletResponse response, Exception exception) {
    assertTrue(interceptor.preHandle(request, response, new Object()));
    interceptor.afterCompletion(request, response, new Object(), exception);
  }

  private AuditClient.AuditRecord capture() {
    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    assertFalse(captor.getValue().action().isBlank());
    return captor.getValue();
  }
}

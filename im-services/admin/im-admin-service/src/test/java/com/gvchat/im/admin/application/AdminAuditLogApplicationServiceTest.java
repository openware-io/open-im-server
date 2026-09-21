package com.gvchat.im.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.im.admin.api.AdminAuditLogPage;
import com.gvchat.im.admin.application.query.AdminAuditLogApplicationService;
import com.gvchat.im.admin.integration.AuditQueryClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 审计日志查询：过滤参数映射 + 分页结构回填。 */
class AdminAuditLogApplicationServiceTest {

  private final AuditQueryClient auditQueryClient = mock(AuditQueryClient.class);
  private final AdminAuditLogApplicationService service = new AdminAuditLogApplicationService(auditQueryClient);

  @Test
  @SuppressWarnings("unchecked")
  void shouldForwardFiltersAndMapPage() {
    when(auditQueryClient.search(any())).thenReturn(Map.of(
        "items", List.of(Map.of("action", "im-user.delete", "resourceId", "71")),
        "total", 3,
        "page", 2,
        "pageSize", 50,
        "totalPages", 1,
        "totalCapped", false));

    AdminAuditLogPage page = service.list(2, 50, "im-user.delete", "user_account", "admin", null,
        "2026-09-01", "2026-09-30");

    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(auditQueryClient).search(captor.capture());
    Map<String, Object> filters = captor.getValue();
    assertThat(filters).containsEntry("page", 2).containsEntry("pageSize", 50)
        .containsEntry("action", "im-user.delete").containsEntry("resourceType", "user_account")
        .containsEntry("operatorKeyword", "admin").containsEntry("from", "2026-09-01")
        .containsEntry("to", "2026-09-30");
    // 未传的筛选项不下发，避免把空串当过滤值。
    assertThat(filters).doesNotContainKey("result");

    assertThat(page.total()).isEqualTo(3L);
    assertThat(page.page()).isEqualTo(2);
    assertThat(page.items()).hasSize(1);
  }

  @Test
  void shouldFallBackToSafeDefaultsAndClampPageSize() {
    when(auditQueryClient.search(any())).thenReturn(Map.of());

    AdminAuditLogPage page = service.list(null, 9999, null, null, null, null, null, null);

    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(auditQueryClient).search(captor.capture());
    assertThat(captor.getValue()).containsEntry("page", 1).containsEntry("pageSize", 200);
    assertThat(page.items()).isEmpty();
    assertThat(page.total()).isZero();
  }
}

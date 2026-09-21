package com.gvchat.im.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gvchat.common.dto.DeleteAdminUserDto;
import com.gvchat.common.exception.ApiException;
import com.gvchat.im.admin.application.command.AdminUserDeletionApplicationService;
import com.gvchat.im.admin.integration.AdminReadClient;
import com.gvchat.im.user.api.admin.AdminUserDeleteCascade;
import com.gvchat.im.user.api.admin.AdminUserDeleteResponse;
import com.gvchat.infrastructure.audit.AuditClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * IM 后台删除用户（IM 侧口径）：转发参数与审计、不能删自己；404/400/409 由用户服务原样返回。
 *
 * <p>IM 用户的删除是 IM 自己的业务：不再有任何 SaaS 域（员工 / 平台运营 / 后台账号映射）门禁。
 */
class AdminUserDeletionApplicationServiceTest {

  private final AdminReadClient adminReadClient = mock(AdminReadClient.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final AdminUserDeletionApplicationService service =
      new AdminUserDeletionApplicationService(adminReadClient, auditClient);

  @Test
  void shouldForwardDeleteAndWriteAudit() {
    when(adminReadClient.deleteUser(71L, 1L, "im_71")).thenReturn(deletedResponse());
    when(auditClient.recordAsync(any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(9L));

    AdminUserDeleteResponse response = service.deleteUser(71L, 1L, "admin",
        DeleteAdminUserDto.builder().confirmUsername("im_71").build());

    assertThat(response.deleted()).isTrue();
    assertThat(response.username()).isEqualTo("im_71");
    verify(adminReadClient).deleteUser(71L, 1L, "im_71");

    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    AuditClient.AuditRecord record = captor.getValue();
    assertThat(record.action()).isEqualTo(AdminUserDeletionApplicationService.AUDIT_ACTION_DELETE);
    assertThat(record.actionLabel()).isEqualTo("删除 IM 用户");
    assertThat(record.resourceType()).isEqualTo("user_account");
    assertThat(record.resourceId()).isEqualTo("71");
    assertThat(record.resourceName()).isEqualTo("im_71");
    assertThat(record.operatorId()).isEqualTo(1L);
    assertThat(record.operatorName()).isEqualTo("admin");
    assertThat(record.detailJson()).contains("im_71").contains("messagesPreserved")
        .contains("deviceSessions").contains("unifiedAccountRetained");
    // 审计 detail 不得落手机号/邮箱等 PII。
    assertThat(record.detailJson()).doesNotContain("13800000000").doesNotContain("a@b.com");
  }

  @Test
  void shouldRefuseDeletingSelf() {
    assertThatThrownBy(() -> service.deleteUser(7L, 7L, "admin",
        DeleteAdminUserDto.builder().confirmUsername("admin").build()))
        .isInstanceOf(ApiException.class)
        .satisfies(exception -> {
          ApiException apiException = (ApiException) exception;
          assertThat(apiException.getStatus()).isEqualTo(409);
          assertThat(apiException.getCode()).isEqualTo("CANNOT_DELETE_SELF");
        });

    verifyNoInteractions(adminReadClient, auditClient);
  }

  /** 用户名不一致：由用户服务强校验后回 400，后台原样透传（本层不做二次判定）。 */
  @Test
  void shouldPropagateUsernameMismatchFromUserService() {
    when(adminReadClient.deleteUser(71L, 1L, "im_72")).thenThrow(
        new ApiException(400, "USERNAME_CONFIRM_MISMATCH", "输入的用户名与当前账号不一致，未执行删除"));

    assertThatThrownBy(() -> service.deleteUser(71L, 1L, "ops",
        DeleteAdminUserDto.builder().confirmUsername("im_72").build()))
        .isInstanceOf(ApiException.class)
        .satisfies(exception -> {
          ApiException apiException = (ApiException) exception;
          assertThat(apiException.getStatus()).isEqualTo(400);
          assertThat(apiException.getCode()).isEqualTo("USERNAME_CONFIRM_MISMATCH");
        });

    verifyNoInteractions(auditClient);
  }

  /** 账号不存在：用户服务 404 原样透传（重复删除同一 id 也走这里，不落 500）。 */
  @Test
  void shouldPropagateNotFoundFromUserService() {
    when(adminReadClient.deleteUser(71L, 1L, "im_71")).thenThrow(
        new ApiException(404, "USER_NOT_FOUND", "账号不存在或已被删除"));

    assertThatThrownBy(() -> service.deleteUser(71L, 1L, "ops",
        DeleteAdminUserDto.builder().confirmUsername("im_71").build()))
        .isInstanceOf(ApiException.class)
        .satisfies(exception -> {
          ApiException apiException = (ApiException) exception;
          assertThat(apiException.getStatus()).isEqualTo(404);
          assertThat(apiException.getCode()).isEqualTo("USER_NOT_FOUND");
        });

    verifyNoInteractions(auditClient);
  }

  /** IM 侧管理员账号（{@code user.role='admin'}）：用户服务 409 原样透传。 */
  @Test
  void shouldPropagateImAdminAccountRejection() {
    when(adminReadClient.deleteUser(1L, 9L, "admin")).thenThrow(
        new ApiException(409, "ADMIN_ACCOUNT_UNDELETABLE", "管理员账号不允许删除"));

    assertThatThrownBy(() -> service.deleteUser(1L, 9L, "ops",
        DeleteAdminUserDto.builder().confirmUsername("admin").build()))
        .isInstanceOf(ApiException.class)
        .satisfies(exception -> {
          ApiException apiException = (ApiException) exception;
          assertThat(apiException.getStatus()).isEqualTo(409);
          assertThat(apiException.getCode()).isEqualTo("ADMIN_ACCOUNT_UNDELETABLE");
        });

    verifyNoInteractions(auditClient);
  }

  private static AdminUserDeleteResponse deletedResponse() {
    return new AdminUserDeleteResponse(true, "im_71", "deleted_71_ab12cd34", true,
        new AdminUserDeleteCascade(1, 2, 1, 1, 1, 1, 3, 1, 4, 0, 0, 0, 0, 1, 0, 1, 1, 1, false, "CUSTOMER", 1));
  }
}

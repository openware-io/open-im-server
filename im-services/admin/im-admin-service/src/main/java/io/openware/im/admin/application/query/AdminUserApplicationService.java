package io.openware.im.admin.application.query;

import io.openware.im.admin.integration.AdminReadClient;
import io.openware.common.dto.AdminListUsersDto;
import io.openware.common.dto.AdminUpdateUserStatusDto;
import io.openware.common.dto.PageResult;
import io.openware.im.user.api.admin.AdminUserResponse;
import io.openware.im.user.api.admin.ChangeUserStatusResponse;
import io.openware.infrastructure.audit.AuditClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserApplicationService {

  /** 审计动作码：IM 后台变更用户状态（与「删除用户」同属用户管理动作，审计日志页一并可查）。 */
  public static final String AUDIT_ACTION_STATUS_UPDATE = "im-user.status.update";

  private final AdminReadClient adminReadClient;
  private final AuditClient auditClient;

  public PageResult<AdminUserResponse> listUsers(AdminListUsersDto query) {
    return adminReadClient.listUsers(query);
  }

  public AdminUserResponse getUserDetail(Long id) {
    return adminReadClient.getUser(id);
  }

  public ChangeUserStatusResponse updateUserStatus(Long id, Long operatorId, AdminUpdateUserStatusDto command) {
    ChangeUserStatusResponse response = adminReadClient.updateUserStatus(id, operatorId, command);
    recordStatusAudit(id, operatorId, response);
    return response;
  }

  /**
   * 状态变更留痕：记目标账号、操作人、变更前后状态与操作理由。
   *
   * <p>审计失败只告警不阻塞业务（状态已经改成功，不能因为审计通道抖动把成功返回改成失败）。
   */
  private void recordStatusAudit(Long id, Long operatorId, ChangeUserStatusResponse response) {
    try {
      auditClient.recordAsync(AuditClient.AuditRecord.builder()
          .action(AUDIT_ACTION_STATUS_UPDATE)
          .actionLabel("IM 用户状态变更")
          .resourceType("user_account")
          .resourceId(String.valueOf(id))
          .operatorId(operatorId)
          .idempotencyKey("im-user-status:" + id + ":" + response.statusVersion())
          .detailJson("{\"previousStatus\":\"" + response.previousStatus() + "\",\"status\":\""
              + response.status() + "\",\"statusVersion\":" + response.statusVersion() + "}")
          .build());
    } catch (RuntimeException failure) {
      log.warn("写用户状态变更审计失败, userId={}, cause={}", id, failure.getMessage());
    }
  }
}

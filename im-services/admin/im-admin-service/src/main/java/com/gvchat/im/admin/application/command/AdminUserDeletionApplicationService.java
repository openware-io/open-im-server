package com.gvchat.im.admin.application.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.common.dto.DeleteAdminUserDto;
import com.gvchat.common.exception.ApiException;
import com.gvchat.im.admin.integration.AdminReadClient;
import com.gvchat.im.user.api.admin.AdminUserDeleteResponse;
import com.gvchat.infrastructure.audit.AuditClient;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * IM 后台「用户管理 → 删除账号」命令服务。
 *
 * <p>边界：**IM 用户的删除是 IM 自己的业务**，不引入任何 SaaS/外部域门禁
 * （员工 / 平台运营账号与 IM 账号的关联由后台「换绑 IM 账号」维护，不是靠禁止删除）。
 * 因此守卫只有 IM 侧三条：
 * <ol>
 *   <li>**不能删除自己** → 409 {@code CANNOT_DELETE_SELF}（避免运营把自己锁在门外）；</li>
 *   <li>转发用户服务执行级联删除，用户服务内按顺序强校验：账号不存在 → 404 {@code USER_NOT_FOUND}；
 *       管理员账号（{@code user.role='admin'}）→ 409 {@code ADMIN_ACCOUNT_UNDELETABLE}；
 *       {@code confirmUsername} 与账号当前用户名不一致 → 400 {@code USERNAME_CONFIRM_MISMATCH}。
 *       这些码由用户服务原样返回（本层不做二次翻译）；</li>
 *   <li>删除成功后写 {@code common-audit-service}：action {@code im-user.delete}、resourceType
 *       {@code user_account}，detail 只记被删用户名 + 级联计数（含统一账号是否保留），**不落 PII**。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserDeletionApplicationService {

  /** 审计动作码：删除 IM 用户（已在 SDK {@code AuditActions} 登记中文标签）。 */
  public static final String AUDIT_ACTION_DELETE = "im-user.delete";
  /** 审计资源类型：统一账号（{@code user_account}）。 */
  public static final String AUDIT_RESOURCE_USER_ACCOUNT = "user_account";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final AdminReadClient adminReadClient;
  private final AuditClient auditClient;

  public AdminUserDeleteResponse deleteUser(Long id, Long operatorId, String operatorName,
      DeleteAdminUserDto command) {
    if (operatorId != null && operatorId.equals(id)) {
      throw new ApiException(409, "CANNOT_DELETE_SELF", "不能删除当前登录的账号");
    }
    AdminUserDeleteResponse result = adminReadClient.deleteUser(id, operatorId, command.getConfirmUsername());
    writeAudit(id, operatorId, operatorName, result);
    return result;
  }

  /** 写审计：失败只告警不阻塞（删除本身已成功，审计失败不应向前端报错）。 */
  private void writeAudit(Long id, Long operatorId, String operatorName, AdminUserDeleteResponse result) {
    try {
      auditClient.recordAsync(AuditClient.AuditRecord.builder()
          .action(AUDIT_ACTION_DELETE)
          .actionLabel("删除 IM 用户")
          .resourceType(AUDIT_RESOURCE_USER_ACCOUNT)
          .resourceId(String.valueOf(id))
          .resourceName(result.username())
          .operatorId(operatorId)
          .operatorName(operatorName)
          .idempotencyKey("im-user-delete:" + id)
          .detailJson(detailJson(result))
          .build());
    } catch (RuntimeException failure) {
      log.warn("写删除用户审计失败, userId={}, cause={}", id, failure.getMessage());
    }
  }

  /**
   * 审计 detail：被删用户名 + 级联计数 + 消息保留口径（{@code cascade} 内含统一账号是否保留）。
   *
   * <p>只放用户名、账号类型与计数：手机号/邮箱/设备标识等 PII 一律不落审计。
   */
  private static String detailJson(AdminUserDeleteResponse result) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("username", result.username());
    detail.put("tombstoneUsername", result.tombstoneUsername());
    detail.put("messagesPreserved", result.messagesPreserved());
    detail.put("cascade", result.cascade());
    try {
      return OBJECT_MAPPER.writeValueAsString(detail);
    } catch (Exception exception) {
      // 序列化失败不能让删除回滚：退化成最小可读 JSON（审计详情是辅助信息，不是业务事实来源）。
      return "{\"username\":\"" + result.username() + "\",\"messagesPreserved\":true}";
    }
  }
}

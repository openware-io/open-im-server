package io.openware.im.user.api.controller.internal;

import io.openware.common.dto.PageResult;
import io.openware.common.enums.UserStatus;
import io.openware.im.user.api.admin.AdminDeviceTokenResponse;
import io.openware.im.user.api.admin.AdminFriendResponse;
import io.openware.im.user.api.admin.AdminUserDeleteResponse;
import io.openware.im.user.api.admin.AdminUserResponse;
import io.openware.im.user.api.admin.AdminUserStatsResponse;
import io.openware.im.user.api.admin.AdminUserStickerResponse;
import io.openware.im.user.api.admin.ChangeUserStatusRequest;
import io.openware.im.user.api.admin.ChangeUserStatusResponse;
import io.openware.im.user.application.account.AccountApplicationService;
import io.openware.im.user.application.account.command.ChangeUserStatusCommand;
import io.openware.im.user.application.account.result.ChangeUserStatusResult;
import io.openware.im.user.application.admin.AdminUserDeletionApplicationService;
import io.openware.im.user.application.admin.AdminUserQueryApplicationService;
import io.openware.im.user.application.admin.query.AdminFriendListQuery;
import io.openware.im.user.application.admin.query.AdminUserListQuery;
import io.openware.im.user.application.notificationsetting.NotificationSettingApplicationService;
import io.openware.im.user.domain.account.model.UserAccountStatus;
import io.openware.im.user.domain.notificationsetting.model.UserNotificationSetting;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/admin/users")
@RequiredArgsConstructor
public class InternalAdminUserQueryController {
  private final AdminUserQueryApplicationService adminUserQueryApplicationService;
  private final AccountApplicationService accountApplicationService;
  private final NotificationSettingApplicationService notificationSettingApplicationService;
  private final AdminUserDeletionApplicationService adminUserDeletionApplicationService;

  @GetMapping
  public PageResult<AdminUserResponse> list(@ModelAttribute AdminUserListQuery query) {
    return adminUserQueryApplicationService.listUsers(query);
  }

  @GetMapping("/{id}")
  public AdminUserResponse detail(@PathVariable Long id) {
    return adminUserQueryApplicationService.detailUser(id);
  }

  @PutMapping("/{id}/status")
  public ChangeUserStatusResponse updateStatus(@PathVariable Long id, @Valid @RequestBody ChangeUserStatusRequest request) {
    ChangeUserStatusResult result = accountApplicationService.changeStatus(new ChangeUserStatusCommand(
        request.requestVersion(), id, UserAccountStatus.valueOf(request.status().name()), request.expectedStatusVersion(),
        request.idempotencyKey(), request.operatorId(), request.reason(), request.correlationId()));
    return new ChangeUserStatusResponse(result.responseVersion(), result.userId(),
        UserStatus.valueOf(result.previousStatus().name()), UserStatus.valueOf(result.status().name()), result.statusVersion(),
        result.idempotencyKey(), result.correlationId(), result.occurredAt());
  }

  @GetMapping("/friends")
  public PageResult<AdminFriendResponse> friends(@ModelAttribute AdminFriendListQuery query) {
    return adminUserQueryApplicationService.listFriends(query);
  }

  /**
   * 后台删除用户（IM 后台「用户管理 → 删除」）。
   *
   * <p>必须显式带 {@code confirmUsername} 且与该账号当前用户名一致，服务端强校验；
   * 管理员账号 409 {@code ADMIN_ACCOUNT_UNDELETABLE}，账号不存在 404 {@code USER_NOT_FOUND}。
   */
  @DeleteMapping("/{id}")
  public AdminUserDeleteResponse delete(@PathVariable Long id,
      @RequestParam(required = false) String confirmUsername,
      @RequestParam(required = false) Long operatorId) {
    return adminUserDeletionApplicationService.deleteUser(id, confirmUsername, operatorId);
  }

  @GetMapping("/{userId}/device-tokens")
  public List<AdminDeviceTokenResponse> deviceTokens(@PathVariable Long userId) {
    return adminUserQueryApplicationService.deviceTokens(userId);
  }

  @GetMapping("/push/{userId}/device-tokens")
  public List<Map<String, String>> pushDeviceTokens(@PathVariable Long userId) {
    return adminUserQueryApplicationService.pushDeviceTokens(userId);
  }

  @GetMapping("/device-tokens")
  public PageResult<AdminDeviceTokenResponse> allDeviceTokens(@RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) Long userId) {
    return adminUserQueryApplicationService.allDeviceTokens(page, pageSize, userId);
  }

  @DeleteMapping("/device-tokens/{id}")
  public void disableDeviceToken(@PathVariable Long id) {
    adminUserQueryApplicationService.disableDeviceToken(id);
  }

  @GetMapping("/stickers")
  public PageResult<AdminUserStickerResponse> stickers(@RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) Long userId) {
    return adminUserQueryApplicationService.stickers(page, pageSize, userId);
  }

  @DeleteMapping("/stickers/{id}")
  public void deleteSticker(@PathVariable Long id) {
    adminUserQueryApplicationService.deleteSticker(id);
  }

  /** 供 access-ws 离线推送判断：按消息类型决定是否推送。 */
  @GetMapping("/{userId}/notification-settings")
  public Map<String, Boolean> notificationSettings(@PathVariable long userId) {
    UserNotificationSetting setting = notificationSettingApplicationService.get(userId);
    return Map.of("notifyPrivate", setting.isNotifyPrivate(), "notifyGroup", setting.isNotifyGroup(),
        "notifyChannel", setting.isNotifyChannel());
  }

  @GetMapping("/stats")
  public AdminUserStatsResponse stats(@RequestParam(defaultValue = "0") int days) {
    return adminUserQueryApplicationService.stats(days);
  }
}

package com.gvchat.im.user.api.controller;

import com.gvchat.infrastructure.security.SecurityUser;
import com.gvchat.im.user.api.converter.UserAccountApiConverter;
import com.gvchat.im.user.api.dto.request.ChangePasswordRequest;
import com.gvchat.im.user.api.dto.request.DeleteAccountRequest;
import com.gvchat.im.user.api.dto.request.SearchUserRequest;
import com.gvchat.im.user.api.dto.request.SelfDestructPolicyRequest;
import com.gvchat.im.user.api.dto.request.UpdateNotificationSettingsRequest;
import com.gvchat.im.user.api.dto.request.UpdatePrivacySettingsRequest;
import com.gvchat.im.user.api.dto.request.UpdateProfileRequest;
import com.gvchat.im.user.api.dto.response.NotificationSettingsResponse;
import com.gvchat.im.user.api.dto.response.PrivacySettingsResponse;
import com.gvchat.im.user.api.dto.response.SelfDestructPolicyResponse;
import com.gvchat.im.user.api.dto.response.UserProfileResponse;
import com.gvchat.im.user.application.account.SelfDestructApplicationService;
import com.gvchat.im.user.application.account.result.SelfDestructPolicyResult;
import com.gvchat.im.user.application.notificationsetting.NotificationSettingApplicationService;
import com.gvchat.im.user.application.privacysetting.PrivacySettingApplicationService;
import com.gvchat.im.user.application.profile.ProfileApplicationService;
import com.gvchat.im.user.application.profile.command.ChangePasswordCommand;
import com.gvchat.im.user.application.profile.command.DeleteAccountCommand;
import com.gvchat.im.user.application.profile.command.UpdateProfileCommand;
import com.gvchat.im.user.domain.notificationsetting.model.UserNotificationSetting;
import com.gvchat.im.user.domain.privacysetting.model.UserPrivacySetting;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "用户资料")
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserProfileController {
  private final ProfileApplicationService profileApplicationService;
  private final NotificationSettingApplicationService notificationSettingApplicationService;
  private final PrivacySettingApplicationService privacySettingApplicationService;
  private final SelfDestructApplicationService selfDestructApplicationService;

  @GetMapping("/me")
  public UserProfileResponse getMe(@AuthenticationPrincipal SecurityUser user) {
    return UserAccountApiConverter.toProfileResponse(profileApplicationService.getProfile(user.getId()));
  }

  @GetMapping("/search")
  public List<UserProfileResponse> search(@Valid @ModelAttribute SearchUserRequest request) {
    return profileApplicationService.searchProfiles(request.getKeyword()).stream()
        .map(UserAccountApiConverter::toProfileResponse)
        .toList();
  }

  @GetMapping("/{id}")
  public UserProfileResponse getUser(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id) {
    return UserAccountApiConverter.toProfileResponse(profileApplicationService.getProfileForViewer(user.getId(), id));
  }

  @PutMapping("/me")
  public UserProfileResponse updateMe(
      @AuthenticationPrincipal SecurityUser user, @Valid @RequestBody UpdateProfileRequest request) {
    return UserAccountApiConverter.toProfileResponse(profileApplicationService.updateProfile(
        user.getId(), new UpdateProfileCommand(
            request.getNickname(), request.getAvatar(), request.getEmail(), request.getPhone(),
            request.getSignature())));
  }

  @PutMapping("/me/password")
  public Map<String, Boolean> changePassword(
      @AuthenticationPrincipal SecurityUser user, @Valid @RequestBody ChangePasswordRequest request) {
    profileApplicationService.changePassword(
        user.getId(), new ChangePasswordCommand(request.getCurrentPassword(), request.getNewPassword()));
    return Map.of("ok", true);
  }

  @GetMapping("/me/notification-settings")
  public NotificationSettingsResponse getNotificationSettings(@AuthenticationPrincipal SecurityUser user) {
    return toResponse(notificationSettingApplicationService.get(user.getId()));
  }

  @PutMapping("/me/notification-settings")
  public NotificationSettingsResponse updateNotificationSettings(
      @AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody UpdateNotificationSettingsRequest request) {
    return toResponse(notificationSettingApplicationService.update(
        user.getId(), request.notifyPrivate(), request.notifyGroup(), request.notifyChannel()));
  }

  private NotificationSettingsResponse toResponse(UserNotificationSetting setting) {
    return new NotificationSettingsResponse(
        setting.isNotifyPrivate(), setting.isNotifyGroup(), setting.isNotifyChannel());
  }

  @GetMapping("/me/privacy-settings")
  public PrivacySettingsResponse getPrivacySettings(@AuthenticationPrincipal SecurityUser user) {
    UserPrivacySetting setting = privacySettingApplicationService.get(user.getId());
    return new PrivacySettingsResponse(setting.isAllowGroupFriendRequest(), setting.isHideGroupMemberInfo());
  }

  @PutMapping("/me/privacy-settings")
  public PrivacySettingsResponse updatePrivacySettings(
      @AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody UpdatePrivacySettingsRequest request) {
    UserPrivacySetting setting = privacySettingApplicationService.update(
        user.getId(), request.allowGroupFriendRequest(), request.hideGroupMemberInfo());
    return new PrivacySettingsResponse(setting.isAllowGroupFriendRequest(), setting.isHideGroupMemberInfo());
  }

  @DeleteMapping("/me")
  public Map<String, Boolean> deleteMe(
      @AuthenticationPrincipal SecurityUser user, @Valid @RequestBody DeleteAccountRequest request) {
    profileApplicationService.deleteAccount(user.getId(), new DeleteAccountCommand(request.getPassword()));
    return Map.of("ok", true);
  }

  @GetMapping("/me/self-destruct")
  public SelfDestructPolicyResponse getSelfDestructPolicy(@AuthenticationPrincipal SecurityUser user) {
    return toResponse(selfDestructApplicationService.getPolicy(user.getId()));
  }

  @PutMapping("/me/self-destruct")
  public SelfDestructPolicyResponse setSelfDestructPolicy(
      @AuthenticationPrincipal SecurityUser user, @Valid @RequestBody SelfDestructPolicyRequest request) {
    return toResponse(selfDestructApplicationService.setPolicy(user.getId(), request.policy()));
  }

  private SelfDestructPolicyResponse toResponse(SelfDestructPolicyResult result) {
    return new SelfDestructPolicyResponse(result.policy(), result.selfDestructAt(), result.lastLoginAt());
  }
}

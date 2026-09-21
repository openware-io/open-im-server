package com.gvchat.im.user.api.controller.internal;

import com.gvchat.im.user.api.authorization.PrivateMessageAuthorizationQuery;
import com.gvchat.im.user.api.authorization.PrivateMessageAuthorizationSnapshot;
import com.gvchat.im.user.application.social.FriendAuthorizationQueryService;
import com.gvchat.im.user.application.account.ActiveUserQueryService;
import com.gvchat.im.user.api.authorization.ActiveUsersQuery;
import com.gvchat.im.user.api.authorization.ActiveUsersSnapshot;
import com.gvchat.im.user.api.authorization.UserProfileSummariesQuery;
import com.gvchat.im.user.api.authorization.UserProfileSummary;
import com.gvchat.im.user.api.authorization.UserDeviceKeyQuery;
import com.gvchat.im.user.api.authorization.UserDeviceKeySummary;
import com.gvchat.im.user.application.devicekey.DeviceKeyApplicationService;
import com.gvchat.im.user.application.profile.ProfileApplicationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/user/authorizations")
@RequiredArgsConstructor
public class PrivateMessageAuthorizationController {
  private final FriendAuthorizationQueryService authorizationQueryService;
  private final ActiveUserQueryService activeUserQueryService;
  private final ProfileApplicationService profileApplicationService;
  private final DeviceKeyApplicationService deviceKeyApplicationService;

  @PostMapping("/private-message")
  public PrivateMessageAuthorizationSnapshot authorize(@RequestBody PrivateMessageAuthorizationQuery query) {
    return authorizationQueryService.authorize(query);
  }

  @PostMapping("/active-users")
  public ActiveUsersSnapshot activeUsers(@RequestBody ActiveUsersQuery query) {
    return activeUserQueryService.findActive(query);
  }

  @PostMapping("/profile-summaries")
  public List<UserProfileSummary> profileSummaries(@RequestBody UserProfileSummariesQuery query) {
    return profileApplicationService.findProfileSummaries(query);
  }

  @PostMapping("/mute-status")
  public java.util.Map<Long, Boolean> muteStatus(@RequestBody UserProfileSummariesQuery query) {
    return profileApplicationService.findMuteStatus(query.userIds());
  }

  @PostMapping("/device-keys")
  public List<UserDeviceKeySummary> deviceKeys(@RequestBody UserDeviceKeyQuery query) {
    return deviceKeyApplicationService.findDeviceKeySummaries(query.userIds());
  }
}

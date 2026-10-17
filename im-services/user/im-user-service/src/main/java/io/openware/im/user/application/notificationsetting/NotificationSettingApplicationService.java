package io.openware.im.user.application.notificationsetting;

import io.openware.im.user.domain.notificationsetting.model.UserNotificationSetting;
import io.openware.im.user.domain.notificationsetting.repository.UserNotificationSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationSettingApplicationService {
  private final UserNotificationSettingRepository repository;

  @Transactional(readOnly = true)
  public UserNotificationSetting get(long userId) {
    return repository.findByUserId(userId)
        .orElseGet(() -> UserNotificationSetting.defaults(userId));
  }

  @Transactional
  public UserNotificationSetting update(long userId, boolean notifyPrivate, boolean notifyGroup,
      boolean notifyChannel) {
    UserNotificationSetting current = get(userId);
    current.update(notifyPrivate, notifyGroup, notifyChannel);
    return repository.save(current);
  }
}

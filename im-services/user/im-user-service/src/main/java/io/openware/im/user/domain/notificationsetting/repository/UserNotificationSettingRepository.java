package io.openware.im.user.domain.notificationsetting.repository;

import io.openware.im.user.domain.notificationsetting.model.UserNotificationSetting;
import java.util.Optional;

public interface UserNotificationSettingRepository {
  Optional<UserNotificationSetting> findByUserId(long userId);

  UserNotificationSetting save(UserNotificationSetting setting);
}

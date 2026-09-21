package com.gvchat.im.user.domain.notificationsetting.repository;

import com.gvchat.im.user.domain.notificationsetting.model.UserNotificationSetting;
import java.util.Optional;

public interface UserNotificationSettingRepository {
  Optional<UserNotificationSetting> findByUserId(long userId);

  UserNotificationSetting save(UserNotificationSetting setting);
}

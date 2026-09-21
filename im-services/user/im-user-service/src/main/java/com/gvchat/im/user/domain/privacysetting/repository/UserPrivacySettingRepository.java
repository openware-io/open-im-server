package com.gvchat.im.user.domain.privacysetting.repository;

import com.gvchat.im.user.domain.privacysetting.model.UserPrivacySetting;
import java.util.Optional;

public interface UserPrivacySettingRepository {
  Optional<UserPrivacySetting> findByUserId(long userId);

  UserPrivacySetting save(UserPrivacySetting setting);
}

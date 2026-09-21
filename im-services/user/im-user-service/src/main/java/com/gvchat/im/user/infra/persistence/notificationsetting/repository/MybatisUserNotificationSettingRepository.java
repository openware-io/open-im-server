package com.gvchat.im.user.infra.persistence.notificationsetting.repository;

import com.gvchat.im.user.domain.notificationsetting.model.UserNotificationSetting;
import com.gvchat.im.user.domain.notificationsetting.repository.UserNotificationSettingRepository;
import com.gvchat.im.user.infra.persistence.notificationsetting.mapper.UserNotificationSettingMapper;
import com.gvchat.im.user.infra.persistence.notificationsetting.po.UserNotificationSettingPo;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MybatisUserNotificationSettingRepository implements UserNotificationSettingRepository {
  private final UserNotificationSettingMapper mapper;

  @Override
  public Optional<UserNotificationSetting> findByUserId(long userId) {
    return Optional.ofNullable(mapper.selectById(userId)).map(this::toDomain);
  }

  @Override
  public UserNotificationSetting save(UserNotificationSetting setting) {
    UserNotificationSettingPo po = toPo(setting);
    if (mapper.selectById(setting.getUserId()) == null) {
      mapper.insert(po);
    } else {
      mapper.updateById(po);
    }
    return toDomain(po);
  }

  private UserNotificationSetting toDomain(UserNotificationSettingPo po) {
    UserNotificationSetting setting = new UserNotificationSetting();
    setting.restore(po.getUserId(), bool(po.getNotifyPrivate()), bool(po.getNotifyGroup()),
        bool(po.getNotifyChannel()), po.getCreatedAt(), po.getUpdatedAt());
    return setting;
  }

  private UserNotificationSettingPo toPo(UserNotificationSetting setting) {
    UserNotificationSettingPo po = new UserNotificationSettingPo();
    po.setUserId(setting.getUserId());
    po.setNotifyPrivate(setting.isNotifyPrivate());
    po.setNotifyGroup(setting.isNotifyGroup());
    po.setNotifyChannel(setting.isNotifyChannel());
    po.setCreatedAt(setting.getCreatedAt());
    po.setUpdatedAt(setting.getUpdatedAt());
    return po;
  }

  private static boolean bool(Boolean value) {
    return value != null && value;
  }
}

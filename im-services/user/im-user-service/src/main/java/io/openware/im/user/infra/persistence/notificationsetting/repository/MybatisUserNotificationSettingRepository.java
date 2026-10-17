package io.openware.im.user.infra.persistence.notificationsetting.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.im.user.domain.notificationsetting.model.UserNotificationSetting;
import io.openware.im.user.domain.notificationsetting.repository.UserNotificationSettingRepository;
import io.openware.im.user.infra.persistence.notificationsetting.mapper.UserNotificationSettingMapper;
import io.openware.im.user.infra.persistence.notificationsetting.po.UserNotificationSettingPo;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MybatisUserNotificationSettingRepository implements UserNotificationSettingRepository {
  private final UserNotificationSettingMapper mapper;

  @Override
  public Optional<UserNotificationSetting> findByUserId(long userId) {
    return Optional.ofNullable(selectByUserId(userId)).map(this::toDomain);
  }

  @Override
  public UserNotificationSetting save(UserNotificationSetting setting) {
    UserNotificationSettingPo existing = selectByUserId(setting.getUserId());
    UserNotificationSettingPo po = toPo(setting);
    if (existing == null) {
      po.setCreatedBy(setting.getUserId());
      po.setUpdatedBy(setting.getUserId());
      // 审计列由适配器确定（standards/10 L299）：领域模型不再携带。
      LocalDateTime now = LocalDateTime.now();
      po.setCreatedAt(now);
      po.setUpdatedAt(now);
      mapper.insert(po);
    } else {
      // 主键是自增 id：改自业务键 user_id 后必须用查到的 id 走 updateById。
      po.setId(existing.getId());
      po.setCreatedBy(setting.getUserId());
      po.setUpdatedBy(setting.getUserId());
      po.setUpdatedAt(LocalDateTime.now());
      mapper.updateById(po);
    }
    return toDomain(po);
  }

  /** user_id 已从主键降为唯一键，业务查询一律走 user_id 而不是 selectById。 */
  private UserNotificationSettingPo selectByUserId(long userId) {
    return mapper.selectOne(Wrappers.<UserNotificationSettingPo>lambdaQuery()
        .eq(UserNotificationSettingPo::getUserId, userId));
  }

  private UserNotificationSetting toDomain(UserNotificationSettingPo po) {
    UserNotificationSetting setting = new UserNotificationSetting();
    setting.restore(po.getUserId(), bool(po.getNotifyPrivate()), bool(po.getNotifyGroup()),
        bool(po.getNotifyChannel()));
    return setting;
  }

  private UserNotificationSettingPo toPo(UserNotificationSetting setting) {
    UserNotificationSettingPo po = new UserNotificationSettingPo();
    po.setUserId(setting.getUserId());
    po.setNotifyPrivate(setting.isNotifyPrivate());
    po.setNotifyGroup(setting.isNotifyGroup());
    po.setNotifyChannel(setting.isNotifyChannel());
    return po;
  }

  private static boolean bool(Boolean value) {
    return value != null && value;
  }
}

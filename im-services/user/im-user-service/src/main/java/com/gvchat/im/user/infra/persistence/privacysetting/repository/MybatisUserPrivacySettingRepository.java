package com.gvchat.im.user.infra.persistence.privacysetting.repository;

import com.gvchat.im.user.domain.privacysetting.model.UserPrivacySetting;
import com.gvchat.im.user.domain.privacysetting.repository.UserPrivacySettingRepository;
import com.gvchat.im.user.infra.persistence.privacysetting.mapper.UserPrivacySettingMapper;
import com.gvchat.im.user.infra.persistence.privacysetting.po.UserPrivacySettingPo;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MybatisUserPrivacySettingRepository implements UserPrivacySettingRepository {
  private final UserPrivacySettingMapper mapper;

  @Override
  public Optional<UserPrivacySetting> findByUserId(long userId) {
    return Optional.ofNullable(mapper.selectById(userId)).map(this::toDomain);
  }

  @Override
  public UserPrivacySetting save(UserPrivacySetting setting) {
    UserPrivacySettingPo po = toPo(setting);
    if (mapper.selectById(setting.getUserId()) == null) {
      mapper.insert(po);
    } else {
      mapper.updateById(po);
    }
    return toDomain(po);
  }

  private UserPrivacySetting toDomain(UserPrivacySettingPo po) {
    UserPrivacySetting setting = new UserPrivacySetting();
    setting.restore(po.getUserId(), bool(po.getAllowGroupFriendRequest()), bool(po.getHideGroupMemberInfo()), po.getCreatedAt(), po.getUpdatedAt());
    return setting;
  }

  private UserPrivacySettingPo toPo(UserPrivacySetting setting) {
    UserPrivacySettingPo po = new UserPrivacySettingPo();
    po.setUserId(setting.getUserId());
    po.setAllowGroupFriendRequest(setting.isAllowGroupFriendRequest());
    po.setHideGroupMemberInfo(setting.isHideGroupMemberInfo());
    po.setCreatedAt(setting.getCreatedAt());
    po.setUpdatedAt(setting.getUpdatedAt());
    return po;
  }

  private static boolean bool(Boolean value) {
    return value != null && value;
  }
}

package io.openware.im.user.infra.persistence.privacysetting.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.im.user.domain.privacysetting.model.UserPrivacySetting;
import io.openware.im.user.domain.privacysetting.repository.UserPrivacySettingRepository;
import io.openware.im.user.infra.persistence.privacysetting.mapper.UserPrivacySettingMapper;
import io.openware.im.user.infra.persistence.privacysetting.po.UserPrivacySettingPo;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MybatisUserPrivacySettingRepository implements UserPrivacySettingRepository {
  private final UserPrivacySettingMapper mapper;

  @Override
  public Optional<UserPrivacySetting> findByUserId(long userId) {
    return Optional.ofNullable(selectByUserId(userId)).map(this::toDomain);
  }

  @Override
  public UserPrivacySetting save(UserPrivacySetting setting) {
    UserPrivacySettingPo existing = selectByUserId(setting.getUserId());
    UserPrivacySettingPo po = toPo(setting);
    if (existing == null) {
      po.setCreatedBy(setting.getUserId());
      po.setUpdatedBy(setting.getUserId());
      mapper.insert(po);
    } else {
      // 主键是自增 id：改自业务键 user_id 后必须用查到的 id 走 updateById。
      po.setId(existing.getId());
      po.setCreatedBy(setting.getUserId());
      po.setUpdatedBy(setting.getUserId());
      mapper.updateById(po);
    }
    return toDomain(po);
  }

  /** user_id 已从主键降为唯一键，业务查询一律走 user_id 而不是 selectById。 */
  private UserPrivacySettingPo selectByUserId(long userId) {
    return mapper.selectOne(Wrappers.<UserPrivacySettingPo>lambdaQuery()
        .eq(UserPrivacySettingPo::getUserId, userId));
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

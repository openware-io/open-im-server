package io.openware.im.user.application.privacysetting;

import io.openware.im.user.domain.privacysetting.model.UserPrivacySetting;
import io.openware.im.user.domain.privacysetting.repository.UserPrivacySettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PrivacySettingApplicationService {
  private final UserPrivacySettingRepository repository;

  @Transactional(readOnly = true)
  public UserPrivacySetting get(long userId) {
    return repository.findByUserId(userId)
        .orElseGet(() -> UserPrivacySetting.defaults(userId));
  }

  @Transactional
  public UserPrivacySetting update(long userId, boolean allowGroupFriendRequest, Boolean hideGroupMemberInfo) {
    UserPrivacySetting current = get(userId);
    // 兼容旧客户端：未携带 hideGroupMemberInfo 时保持现有值（无记录则默认开启保护）。
    boolean hide = hideGroupMemberInfo != null ? hideGroupMemberInfo : current.isHideGroupMemberInfo();
    current.update(allowGroupFriendRequest, hide);
    return repository.save(current);
  }
}

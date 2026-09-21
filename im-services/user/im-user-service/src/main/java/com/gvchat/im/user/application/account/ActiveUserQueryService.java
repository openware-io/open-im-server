package com.gvchat.im.user.application.account;

import com.gvchat.im.user.api.authorization.ActiveUsersQuery;
import com.gvchat.im.user.api.authorization.ActiveUsersSnapshot;
import com.gvchat.im.user.domain.account.model.UserAccountStatus;
import com.gvchat.im.user.domain.account.repository.UserAccountRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ActiveUserQueryService {
  private final UserAccountRepository userAccountRepository;

  @Transactional(readOnly = true)
  public ActiveUsersSnapshot findActive(ActiveUsersQuery query) {
    List<Long> ids = query.userIds() == null ? List.of() : query.userIds().stream()
        .filter(java.util.Objects::nonNull).filter(id -> id > 0).distinct().toList();
    return new ActiveUsersSnapshot(userAccountRepository.findByIds(ids).stream()
        .filter(account -> account.getStatus() != UserAccountStatus.DISABLED).map(account -> account.getId()).toList());
  }
}

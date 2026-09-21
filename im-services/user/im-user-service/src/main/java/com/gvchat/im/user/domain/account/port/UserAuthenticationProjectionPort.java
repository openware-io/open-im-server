package com.gvchat.im.user.domain.account.port;

import com.gvchat.im.user.domain.account.model.UserAuthenticationSnapshot;

public interface UserAuthenticationProjectionPort {
  void save(UserAuthenticationSnapshot snapshot);
}

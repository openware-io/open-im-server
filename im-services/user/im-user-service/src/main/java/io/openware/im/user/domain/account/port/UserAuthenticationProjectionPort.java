package io.openware.im.user.domain.account.port;

import io.openware.im.user.domain.account.model.UserAuthenticationSnapshot;

public interface UserAuthenticationProjectionPort {
  void save(UserAuthenticationSnapshot snapshot);
}

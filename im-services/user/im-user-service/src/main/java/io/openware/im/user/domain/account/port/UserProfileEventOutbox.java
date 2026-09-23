package io.openware.im.user.domain.account.port;

import io.openware.im.user.domain.account.event.UserProfileChanged;

public interface UserProfileEventOutbox {
  void append(UserProfileChanged event);
}

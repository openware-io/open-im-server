package io.openware.im.user.domain.account.repository;

import io.openware.im.user.domain.account.event.UserProfileChanged;

public interface UserProfileEventOutbox {
  void append(UserProfileChanged event);
}

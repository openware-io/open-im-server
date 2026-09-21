package com.gvchat.im.user.domain.account.port;

import com.gvchat.im.user.domain.account.event.UserProfileChanged;

public interface UserProfileEventOutbox {
  void append(UserProfileChanged event);
}

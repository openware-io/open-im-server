package com.gvchat.im.user.domain.account.port;

import com.gvchat.im.user.domain.account.event.UserChatRecordsPurged;
import com.gvchat.im.user.domain.account.event.UserAuthenticationInvalidated;
import com.gvchat.im.user.domain.account.event.UserDataWipeRequested;
import com.gvchat.im.user.domain.account.event.UserStatusChanged;

public interface UserStatusEventOutbox {
  void append(UserStatusChanged event);

  void append(UserAuthenticationInvalidated event);

  void append(UserChatRecordsPurged event);

  void append(UserDataWipeRequested event);
}

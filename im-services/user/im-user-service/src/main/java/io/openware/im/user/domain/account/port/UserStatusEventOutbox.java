package io.openware.im.user.domain.account.port;

import io.openware.im.user.domain.account.event.UserChatRecordsPurged;
import io.openware.im.user.domain.account.event.UserAuthenticationInvalidated;
import io.openware.im.user.domain.account.event.UserDataWipeRequested;
import io.openware.im.user.domain.account.event.UserStatusChanged;

public interface UserStatusEventOutbox {
  void append(UserStatusChanged event);

  void append(UserAuthenticationInvalidated event);

  void append(UserChatRecordsPurged event);

  void append(UserDataWipeRequested event);
}

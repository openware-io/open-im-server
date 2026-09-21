package com.gvchat.im.user.domain.social.port;

import com.gvchat.im.user.domain.social.event.FriendAccepted;
import com.gvchat.im.user.domain.social.event.FriendRequested;

public interface FriendEventOutbox {
  void append(FriendRequested event);
  void append(FriendAccepted event);
}

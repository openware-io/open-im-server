package io.openware.im.user.domain.social.repository;

import io.openware.im.user.domain.social.event.FriendAccepted;
import io.openware.im.user.domain.social.event.FriendRequested;

public interface FriendEventOutbox {
  void append(FriendRequested event);
  void append(FriendAccepted event);
}

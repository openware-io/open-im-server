package io.openware.im.message.domain.message.port;

public interface FriendRelationPort {
  boolean areFriends(long userId, long peerUserId);
}

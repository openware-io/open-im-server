package io.openware.im.message.domain.message.repository;

public interface FriendRelationPort {
  boolean areFriends(long userId, long peerUserId);
}

package com.gvchat.im.user.domain.social.model;

public enum FriendRequestStatus {
  PENDING,
  ACCEPTED,
  REJECTED;

  public static FriendRequestStatus fromAction(String action) {
    return switch (action) {
      case "accepted" -> ACCEPTED;
      case "rejected" -> REJECTED;
      default -> throw new IllegalArgumentException("Unknown FriendRequestStatus: " + action);
    };
  }
}

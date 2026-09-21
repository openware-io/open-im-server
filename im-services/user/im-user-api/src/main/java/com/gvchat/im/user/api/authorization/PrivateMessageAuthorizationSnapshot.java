package com.gvchat.im.user.api.authorization;

public record PrivateMessageAuthorizationSnapshot(
    long userId,
    long peerUserId,
    String relationStatus,
    long authorizationVersion,
    boolean allowed,
    String denialCode) {
}

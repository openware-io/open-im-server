package io.openware.im.user.api.authorization;

public record UserProfileSummary(long userId, String username, String nickname, String avatar) {
}

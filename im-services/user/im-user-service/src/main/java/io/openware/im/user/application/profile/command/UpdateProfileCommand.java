package io.openware.im.user.application.profile.command;

public record UpdateProfileCommand(
    String nickname, String avatar, String email, String phone, String signature) {
}

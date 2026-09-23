package io.openware.im.user.application.profile.command;

public record ChangePasswordCommand(String currentPassword, String newPassword) {
}

package com.gvchat.im.user.application.profile.command;

public record ChangePasswordCommand(String currentPassword, String newPassword) {
}

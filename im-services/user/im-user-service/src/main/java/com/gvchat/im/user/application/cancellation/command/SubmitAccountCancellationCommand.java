package com.gvchat.im.user.application.cancellation.command;

public record SubmitAccountCancellationCommand(Long userId, String password, String source, String ip) {
}

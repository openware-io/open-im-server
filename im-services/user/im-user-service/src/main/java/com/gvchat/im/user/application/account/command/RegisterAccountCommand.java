package com.gvchat.im.user.application.account.command;

public record RegisterAccountCommand(String username, String password, String nickname, String email, String phone) {
}

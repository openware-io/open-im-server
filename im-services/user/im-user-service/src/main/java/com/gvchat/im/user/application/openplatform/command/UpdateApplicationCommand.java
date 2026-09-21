package com.gvchat.im.user.application.openplatform.command;

import java.util.List;

public record UpdateApplicationCommand(
    String callbackUrl,
    List<String> scopes) {
}

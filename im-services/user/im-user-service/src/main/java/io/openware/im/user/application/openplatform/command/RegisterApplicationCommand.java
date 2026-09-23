package io.openware.im.user.application.openplatform.command;

import java.util.List;

public record RegisterApplicationCommand(
    String appName,
    String subjectName,
    String appType,
    String callbackUrl,
    List<String> scopes) {
}

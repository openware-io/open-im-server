package com.gvchat.im.user.application.openplatform.result;

import java.time.LocalDateTime;
import java.util.List;

public record ApplicationResult(
    String appId,
    String appName,
    String subjectName,
    String appType,
    String callbackUrl,
    List<String> scopes,
    String status,
    String rejectReason,
    LocalDateTime reviewedAt) {
}

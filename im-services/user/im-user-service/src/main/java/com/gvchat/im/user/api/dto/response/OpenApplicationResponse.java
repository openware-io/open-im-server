package com.gvchat.im.user.api.dto.response;

import java.util.List;

public record OpenApplicationResponse(
    String appId,
    String appName,
    String appType,
    String callbackUrl,
    List<String> scopes,
    String status,
    String rejectReason) {
}

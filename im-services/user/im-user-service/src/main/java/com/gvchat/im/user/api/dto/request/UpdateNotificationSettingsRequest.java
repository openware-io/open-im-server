package com.gvchat.im.user.api.dto.request;

import jakarta.validation.constraints.NotNull;

/** 更新离线推送通知设置请求（私聊/群聊/频道三类分别开关）。 */
public record UpdateNotificationSettingsRequest(
    @NotNull Boolean notifyPrivate,
    @NotNull Boolean notifyGroup,
    @NotNull Boolean notifyChannel) {
}

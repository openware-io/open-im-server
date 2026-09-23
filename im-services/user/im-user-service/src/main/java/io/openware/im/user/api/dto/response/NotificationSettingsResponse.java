package io.openware.im.user.api.dto.response;

/** 用户离线推送通知设置（私聊/群聊/频道）。 */
public record NotificationSettingsResponse(
    boolean notifyPrivate,
    boolean notifyGroup,
    boolean notifyChannel) {
}

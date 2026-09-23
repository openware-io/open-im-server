package io.openware.im.user.api.dto.response;

/** 用户隐私设置响应。 */
public record PrivacySettingsResponse(boolean allowGroupFriendRequest, boolean hideGroupMemberInfo) {
}

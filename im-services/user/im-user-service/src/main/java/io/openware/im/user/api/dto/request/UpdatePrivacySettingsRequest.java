package io.openware.im.user.api.dto.request;

import jakarta.validation.constraints.NotNull;

/** 更新用户隐私设置请求。 */
public record UpdatePrivacySettingsRequest(
    @NotNull Boolean allowGroupFriendRequest,
    Boolean hideGroupMemberInfo) {
}

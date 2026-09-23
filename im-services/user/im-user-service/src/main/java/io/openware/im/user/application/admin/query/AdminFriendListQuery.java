package io.openware.im.user.application.admin.query;

import io.openware.common.enums.FriendStatus;

public record AdminFriendListQuery(String keyword, FriendStatus status, String groupName, Integer page, Integer pageSize) {
}

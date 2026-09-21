package com.gvchat.im.user.application.admin.query;

import com.gvchat.common.enums.FriendStatus;

public record AdminFriendListQuery(String keyword, FriendStatus status, String groupName, Integer page, Integer pageSize) {
}

package com.gvchat.im.user.application.admin.query;

import com.gvchat.common.enums.UserStatus;

public record AdminUserListQuery(String username, String keyword, UserStatus status, Integer page, Integer pageSize) {
}

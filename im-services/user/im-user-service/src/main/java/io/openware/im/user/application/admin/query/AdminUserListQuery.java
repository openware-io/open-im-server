package io.openware.im.user.application.admin.query;

import io.openware.common.enums.UserStatus;

public record AdminUserListQuery(String username, String keyword, UserStatus status, Integer page, Integer pageSize) {
}

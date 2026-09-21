package com.gvchat.im.user.api.authorization;

import java.util.List;

public record ActiveUsersQuery(List<Long> userIds) {
}

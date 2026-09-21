package com.gvchat.im.user.api.authorization;

import java.util.List;

public record ActiveUsersSnapshot(List<Long> activeUserIds) {
}

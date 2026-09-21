package com.gvchat.im.user.api.authorization;

import java.util.List;

public record UserProfileSummariesQuery(List<Long> userIds) {
}

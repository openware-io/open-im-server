package com.gvchat.im.user.api.dto.request;

import jakarta.validation.constraints.Size;

public record UpdateFriendRequest(@Size(max = 128) String remark, @Size(max = 64) String groupName) {
}

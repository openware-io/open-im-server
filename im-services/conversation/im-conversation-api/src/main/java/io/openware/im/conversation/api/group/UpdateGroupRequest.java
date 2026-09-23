package io.openware.im.conversation.api.group;

import jakarta.validation.constraints.Size;

public record UpdateGroupRequest(@Size(max = 128) String name, @Size(max = 512) String avatar, String announcement,
    Boolean allowMemberInvite, Boolean allowMemberFriendRequest, Boolean allowMemberViewAccount) {
}

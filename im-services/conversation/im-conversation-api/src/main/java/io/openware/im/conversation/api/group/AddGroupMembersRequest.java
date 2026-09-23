package io.openware.im.conversation.api.group;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record AddGroupMembersRequest(@NotNull List<Long> userIds) {
}

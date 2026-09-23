package io.openware.im.conversation.api.group;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SetGroupMemberRoleRequest(@NotNull Long userId, @NotBlank String role) {
}

package io.openware.im.conversation.api.group;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateGroupRequest(@NotBlank String name, @Size(max = 512) String avatar, List<Long> memberIds) {
}

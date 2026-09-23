package io.openware.im.user.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SendFriendRequest(@NotNull Long toUserId, @Size(max = 256) String message, String source, Long groupId) {
  /** source 为空时按「直接添加」处理。 */
  public SendFriendRequest(Long toUserId, String message) {
    this(toUserId, message, null, null);
  }
}

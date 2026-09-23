package io.openware.im.user.api.converter;

import io.openware.im.user.api.dto.response.FriendRequestResponse;
import io.openware.im.user.api.dto.response.FriendResponse;
import io.openware.im.user.application.social.result.FriendRequestResult;
import io.openware.im.user.application.social.result.FriendResult;

public final class FriendApiConverter {
  private FriendApiConverter() {
  }

  public static FriendResponse toResponse(FriendResult result) {
    return new FriendResponse(result.id(), result.userId(), result.friendId(), result.friendUsername(),
        result.friendNickname(), result.friendAvatar(), result.remark(), result.groupName(), result.status(),
        result.createdBy(), result.createdAt(), result.updatedBy(), result.updatedAt());
  }

  public static FriendRequestResponse toResponse(FriendRequestResult result) {
    return new FriendRequestResponse(result.id(), result.fromUserId(), result.toUserId(), result.message(),
        result.status(), result.createdBy(), result.createdAt(), result.updatedBy(), result.updatedAt());
  }
}

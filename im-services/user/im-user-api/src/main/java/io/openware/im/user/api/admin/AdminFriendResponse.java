package io.openware.im.user.api.admin;

import io.openware.common.enums.FriendStatus;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminFriendResponse {
  private final Long id;
  private final Long userId;
  private final String userNickname;
  private final Long friendId;
  private final String friendNickname;
  private final String remark;
  private final String groupName;
  private final FriendStatus status;
  private final LocalDateTime createdAt;
}

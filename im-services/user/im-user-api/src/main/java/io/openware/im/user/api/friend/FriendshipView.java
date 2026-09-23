package io.openware.im.user.api.friend;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FriendshipView {
  private Long userId;
  private Long friendId;
  private boolean friends;
  private boolean blocked;
}

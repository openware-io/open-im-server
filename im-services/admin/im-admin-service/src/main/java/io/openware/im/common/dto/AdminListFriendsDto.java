package io.openware.common.dto;

import io.openware.common.enums.FriendStatus;
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
public class AdminListFriendsDto {
  private String keyword;
  private FriendStatus status;
  private String groupName;
  private Integer page;
  private Integer pageSize;
}

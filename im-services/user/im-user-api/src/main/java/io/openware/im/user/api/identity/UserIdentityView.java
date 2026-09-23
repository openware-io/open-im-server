package io.openware.im.user.api.identity;

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
public class UserIdentityView {
  private Long userId;
  private String username;
  private String nickname;
  private String status;
}

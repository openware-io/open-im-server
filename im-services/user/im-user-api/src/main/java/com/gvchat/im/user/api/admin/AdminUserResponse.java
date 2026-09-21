package com.gvchat.im.user.api.admin;

import com.gvchat.common.enums.UserRole;
import com.gvchat.common.enums.UserStatus;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminUserResponse {
  private final Long id;
  private final String username;
  private final String nickname;
  private final String avatar;
  private final String email;
  private final String phone;
  private final String signature;
  private final UserStatus status;
  private final long statusVersion;
  private final UserRole role;
  private final LocalDateTime createdAt;
  private final LocalDateTime updatedAt;
}

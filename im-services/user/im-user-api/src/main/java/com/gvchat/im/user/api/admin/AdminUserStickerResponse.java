package com.gvchat.im.user.api.admin;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminUserStickerResponse {
  private final Long id;
  private final Long userId;
  private final String username;
  private final String nickname;
  private final String url;
  private final String thumbnail;
  private final Integer sortOrder;
  private final LocalDateTime createdAt;
}

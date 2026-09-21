package com.gvchat.im.user.api.admin;

import com.gvchat.common.enums.ClientPlatform;
import com.gvchat.common.enums.PushProvider;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminDeviceTokenResponse {
  private final Long id;
  private final Long userId;
  private final String tokenFingerprint;
  private final PushProvider pushProvider;
  private final ClientPlatform platform;
  private final String deviceId;
  private final Boolean enabled;
  private final LocalDateTime createdAt;
  private final LocalDateTime updatedAt;
}

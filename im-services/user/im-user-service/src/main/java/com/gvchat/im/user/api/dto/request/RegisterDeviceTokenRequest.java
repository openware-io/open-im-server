package com.gvchat.im.user.api.dto.request;

import com.gvchat.common.enums.ClientPlatform;
import com.gvchat.common.enums.PushProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterDeviceTokenRequest {
  @NotBlank
  private String token;
  @NotNull
  private ClientPlatform platform;
  private PushProvider pushProvider;
  private String deviceId;
}

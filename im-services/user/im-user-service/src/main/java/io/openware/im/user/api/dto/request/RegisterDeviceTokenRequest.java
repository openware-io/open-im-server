package io.openware.im.user.api.dto.request;

import io.openware.common.enums.ClientPlatform;
import io.openware.common.enums.PushProvider;
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

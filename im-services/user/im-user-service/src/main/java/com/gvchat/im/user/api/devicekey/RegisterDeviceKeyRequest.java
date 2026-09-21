package com.gvchat.im.user.api.devicekey;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterDeviceKeyRequest {
  @NotBlank
  private String deviceId;
  @NotBlank
  private String publicKey;
}

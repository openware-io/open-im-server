package com.gvchat.im.user.api.dto.request;

import com.gvchat.common.enums.PushProvider;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RemoveDeviceTokenRequest {
  @NotBlank
  private String token;
  private PushProvider pushProvider;
}

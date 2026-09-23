package io.openware.im.user.api.dto.request;

import io.openware.common.enums.PushProvider;
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

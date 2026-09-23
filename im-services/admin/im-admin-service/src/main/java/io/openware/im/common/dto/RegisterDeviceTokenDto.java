package io.openware.common.dto;

import io.openware.common.enums.ClientPlatform;
import io.openware.common.enums.PushProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
public class RegisterDeviceTokenDto {
  @NotBlank
  private String token;

  @NotNull
  private ClientPlatform platform;

  private PushProvider pushProvider;

  private String deviceId;
}

package com.gvchat.common.dto;

import com.gvchat.common.enums.PushProvider;
import jakarta.validation.constraints.NotBlank;
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
public class RemoveDeviceTokenDto {
  @NotBlank
  private String token;

  private PushProvider pushProvider;
}

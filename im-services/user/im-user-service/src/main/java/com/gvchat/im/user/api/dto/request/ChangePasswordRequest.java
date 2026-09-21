package com.gvchat.im.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangePasswordRequest {
  @NotBlank
  private String currentPassword;
  @NotBlank
  @Size(min = 6, max = 128)
  private String newPassword;
}

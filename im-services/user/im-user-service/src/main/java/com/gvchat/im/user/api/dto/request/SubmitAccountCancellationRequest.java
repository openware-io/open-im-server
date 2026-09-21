package com.gvchat.im.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SubmitAccountCancellationRequest {
  @NotBlank
  private String password;
  private String source;
}

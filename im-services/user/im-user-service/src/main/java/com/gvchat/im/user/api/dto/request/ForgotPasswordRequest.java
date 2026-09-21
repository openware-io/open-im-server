package com.gvchat.im.user.api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** 邮箱找回密码请求。 */
@Getter
@Setter
public class ForgotPasswordRequest {
  @NotBlank
  @Email
  private String email;
}

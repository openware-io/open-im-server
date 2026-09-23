package io.openware.im.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** 手机验证码找回密码请求：下发短信验证码。 */
@Getter
@Setter
public class ForgotPasswordBySmsRequest {
  @NotBlank
  private String phone;
}

package com.gvchat.im.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 凭手机验证码设置新密码请求。 */
@Getter
@Setter
public class ResetPasswordBySmsRequest {
  @NotBlank
  private String phone;

  @NotBlank
  private String code;

  @NotBlank
  @Size(min = 6, max = 128)
  private String newPassword;
}

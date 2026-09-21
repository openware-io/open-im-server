package com.gvchat.im.user.api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 注册请求：邮箱为必填（找回密码依据），但当前版本不做邮箱验证。 */
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest {
  @NotBlank
  @Size(min = 3, max = 64)
  private String username;
  @NotBlank
  @Size(min = 6, max = 128)
  private String password;
  @Size(max = 128)
  private String nickname;
  @NotBlank
  @Email
  private String email;
  @Size(max = 30)
  private String phone;
}

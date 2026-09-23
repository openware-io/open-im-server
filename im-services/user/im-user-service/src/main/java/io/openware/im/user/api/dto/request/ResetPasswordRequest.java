package io.openware.im.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 凭重置令牌设置新密码请求。 */
@Getter
@Setter
public class ResetPasswordRequest {
  @NotBlank
  private String token;

  @NotBlank
  @Size(min = 6, max = 128)
  private String newPassword;
}

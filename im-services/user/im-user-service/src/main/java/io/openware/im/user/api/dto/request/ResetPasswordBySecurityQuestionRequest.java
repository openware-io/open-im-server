package io.openware.im.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 密保问题找回密码请求。 */
@Getter
@Setter
public class ResetPasswordBySecurityQuestionRequest {
  @NotBlank
  private String username;

  @NotBlank
  @Size(max = 128)
  private String question;

  @NotBlank
  private String answer;

  @NotBlank
  @Size(min = 6, max = 128)
  private String newPassword;
}

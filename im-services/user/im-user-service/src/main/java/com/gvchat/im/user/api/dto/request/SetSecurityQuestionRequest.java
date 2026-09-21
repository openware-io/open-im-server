package com.gvchat.im.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 设置（或更新）密保问题请求。 */
@Getter
@Setter
public class SetSecurityQuestionRequest {
  @NotBlank
  @Size(max = 128)
  private String question;

  @NotBlank
  private String answer;
}
